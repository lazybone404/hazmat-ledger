package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import com.lazybone.hazmat.entity.MsdsEncodeTaskStatus;
import com.lazybone.hazmat.msds.MsdsVectorService;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * RabbitMQ 真实链路：投递 -> claim -> 编码 -> DONE。
 *
 * <p>类名以 {@code IT} 结尾，默认的 {@code mvn test} 不会执行；需要显式运行：
 * <pre>
 *   ./mvnw -Dtest=MsdsEncodeMqIT test
 * </pre>
 * 没装 Docker 时 Testcontainers 会跳过。
 *
 * <p>默认模型是本地哈希，不联网；这里验证的是任务队列本身，不是模型质量。
 * 成功路径走完整消费链；DLQ 只验证拓扑（直接向 DLX 发一条消息，看 DLQ 能否收到），
 * 重试耗尽后的状态机由默认套件里的 {@code EncodeTaskMessageRecovererTest} 覆盖，
 * 不在真实 broker 上等退避，避免异步断言因环境波动变红。
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-mq-it;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.ai.embedding-dimensions=64",
        "hazmat.mq.enabled=true",
        "hazmat.mq.encode.relay-interval-ms=1000",
        "hazmat.mq.encode.relay-min-age-ms=500",
        "hazmat.mq.encode.claim-lease-ms=60000",
        "hazmat.mq.encode.retry-initial-interval-ms=200",
        "hazmat.mq.encode.retry-max-interval-ms=400",
        "hazmat.vector.store=memory"
})
class MsdsEncodeMqIT {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    private EncodeTaskDispatcher dispatcher;

    @Autowired
    private EncodeTaskService taskService;

    @Autowired
    private MsdsVectorService vectorService;

    @Autowired
    private MsdsChunkRepository chunkRepository;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    void encodingTaskCompletesThroughRabbitMq() {
        // 确保交换机/队列/绑定已声明；监听容器启动与声明之间存在很短的时间窗
        rabbitAdmin.initialize();
        assertThat(chunkRepository.count()).isGreaterThan(0);

        EncodeTaskView view = dispatcher.requestEncoding(
                vectorService.activeVersion(), "it");
        assertThat(view).isNotNull();
        assertThat(view.id()).isNotNull();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            MsdsEncodeTask task = taskService.find(view.id()).orElseThrow();
            assertThat(task.getStatus()).isEqualTo(MsdsEncodeTaskStatus.DONE);
        });

        assertThat(vectorService.status().pending()).isZero();
        assertThat(vectorService.status().failed()).isZero();
    }

    /**
     * 只验证拓扑：往 DLX 的路由键发一条消息，DLQ 能收到。
     *
     * <p>不经过消费端重试，避免等 2s/4s 退避造成异步断言不稳定；
     * 「重试耗尽后标 DEAD 并拒绝消息」由默认套件里的
     * {@code EncodeTaskMessageRecovererTest} 覆盖，两边各管一层。
     */
    @Test
    void deadLetterExchangeRoutesToDlq() {
        rabbitAdmin.initialize();
        String marker = "it-dlq-" + UUID.randomUUID();
        Message message = new Message(marker.getBytes(StandardCharsets.UTF_8));

        rabbitTemplate.send("hazmat.msds.dlx", "msds.encode.dlq", message);

        Message received = rabbitTemplate.receive("hazmat.msds.encode.dlq", 5000);
        assertThat(received).as("消息应被路由到死信队列").isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8)).isEqualTo(marker);
    }
}
