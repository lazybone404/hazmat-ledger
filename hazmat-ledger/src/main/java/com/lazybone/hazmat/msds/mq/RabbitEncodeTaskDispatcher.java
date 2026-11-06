package com.lazybone.hazmat.msds.mq;

import com.lazybone.hazmat.entity.MsdsEncodeTask;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * MQ 模式下的编码入口：先落任务，再发消息。
 *
 * <p>发送失败不回滚任务、也不让接口报错——任务行已经是事实源，
 * relay 会按 PENDING 状态补发。把「写任务」和「发消息」放在一个事务里是做不到的
 * （broker 不参与数据库事务），所以顺序只能是先库后消息，再靠补偿收口。
 */
@Component
@ConditionalOnProperty(prefix = "hazmat.mq", name = "enabled", havingValue = "true")
public class RabbitEncodeTaskDispatcher implements EncodeTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RabbitEncodeTaskDispatcher.class);

    private final EncodeTaskService taskService;
    private final RabbitTemplate rabbitTemplate;
    private final MsdsMqProperties properties;
    private final MeterRegistry meterRegistry;

    public RabbitEncodeTaskDispatcher(EncodeTaskService taskService,
                                      RabbitTemplate rabbitTemplate,
                                      MsdsMqProperties properties,
                                      MeterRegistry meterRegistry) {
        this.taskService = taskService;
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
        this.meterRegistry = meterRegistry;
    }

    @Override
    public EncodeTaskView requestEncoding(String indexVersion, String reason) {
        MsdsEncodeTask task = taskService.createOrReuse(indexVersion);
        log.info("触发 MSDS 编码任务: id={}, reason={}, indexVersion={}",
                task.getId(), reason, indexVersion);
        publish(task.getId());
        return EncodeTaskView.from(task);
    }

    /**
     * 投递给编码队列。失败只记日志和指标：任务仍是 PENDING，relay 下一轮会重发。
     */
    public void publish(Long taskId) {
        if (taskId == null) {
            return;
        }
        MsdsMqProperties.Encode encode = properties.getEncode();
        try {
            rabbitTemplate.convertAndSend(encode.getExchange(), encode.getRoutingKey(),
                    new EncodeTaskMessage(taskId),
                    message -> {
                        message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                        message.getMessageProperties().setMessageId(String.valueOf(taskId));
                        message.getMessageProperties().setHeader(
                                EncodeTaskMessage.TASK_ID_HEADER, taskId);
                        return message;
                    });
            meterRegistry.counter("msds.encode.task", "event", "published").increment();
        } catch (RuntimeException e) {
            meterRegistry.counter("msds.encode.task", "event", "publish_failed").increment();
            log.warn("MSDS 编码任务投递失败，任务保持 PENDING，等待 relay 补发: id={}, reason={}",
                    taskId, e.getMessage());
        }
    }
}
