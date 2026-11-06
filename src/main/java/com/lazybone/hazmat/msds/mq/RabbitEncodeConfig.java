package com.lazybone.hazmat.msds.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.HashMap;
import java.util.Map;

/**
 * MQ 模式的 RabbitMQ 拓扑、消息转换与消费端重试。
 *
 * <p>只有 {@code hazmat.mq.enabled=true} 时才创建这些 bean。关闭时 Spring Boot
 * 仍然会因为 classpath 上有 spring-rabbit 而准备 RabbitTemplate，但连接是懒的，
 * 没有监听器就不会去连 broker——默认套件和裸 H2 启动不需要 RabbitMQ。
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(prefix = "hazmat.mq", name = "enabled", havingValue = "true")
public class RabbitEncodeConfig {

    @Bean
    DirectExchange msdsEncodeExchange(MsdsMqProperties properties) {
        return new DirectExchange(properties.getEncode().getExchange(), true, false);
    }

    @Bean
    Queue msdsEncodeQueue(MsdsMqProperties properties) {
        Map<String, Object> args = new HashMap<>();
        args.put("x-dead-letter-exchange", properties.getEncode().getDlx());
        args.put("x-dead-letter-routing-key", properties.getEncode().getDlqRoutingKey());
        return QueueBuilder.durable(properties.getEncode().getQueue())
                .withArguments(args)
                .build();
    }

    @Bean
    Binding msdsEncodeBinding(
            @Qualifier("msdsEncodeQueue") Queue queue,
            @Qualifier("msdsEncodeExchange") DirectExchange exchange,
            MsdsMqProperties properties) {
        return BindingBuilder.bind(queue).to(exchange)
                .with(properties.getEncode().getRoutingKey());
    }

    @Bean
    DirectExchange msdsEncodeDlx(MsdsMqProperties properties) {
        return new DirectExchange(properties.getEncode().getDlx(), true, false);
    }

    @Bean
    Queue msdsEncodeDlq(MsdsMqProperties properties) {
        return QueueBuilder.durable(properties.getEncode().getDlq()).build();
    }

    @Bean
    Binding msdsEncodeDlqBinding(
            @Qualifier("msdsEncodeDlq") Queue queue,
            @Qualifier("msdsEncodeDlx") DirectExchange exchange,
            MsdsMqProperties properties) {
        return BindingBuilder.bind(queue).to(exchange)
                .with(properties.getEncode().getDlqRoutingKey());
    }

    /**
     * JSON 转换器只信任本包的类型头。
     *
     * <p>消息里带 {@code __TypeId__} 头才能反序列化成 record；不限制包名等于
     * 允许生产者让消费者实例化任意类，是反序列化漏洞的常见入口。
     */
    @Bean
    MessageConverter msdsEncodeMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.lazybone.hazmat.msds.mq");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }

    @Bean
    EncodeTaskMessageRecoverer msdsEncodeMessageRecoverer(EncodeTaskService taskService,
                                                          io.micrometer.core.instrument.MeterRegistry meterRegistry) {
        return new EncodeTaskMessageRecoverer(taskService, meterRegistry);
    }

    /**
     * 消费端重试：首次 + 2 次重试，退避 2s / 4s。
     *
     * <p>用进程内退避而不是 TTL 重试队列：当前吞吐是「每次 reindex 几百块」，
     * 为它再维护几条重试队列不划算。broker 挂了或实例崩溃时，任务表 + relay 负责兜底。
     */
    @Bean
    RetryOperationsInterceptor msdsEncodeRetryInterceptor(
            EncodeTaskMessageRecoverer recoverer, MsdsMqProperties properties) {
        MsdsMqProperties.Encode encode = properties.getEncode();
        ExponentialBackOffPolicy backoff = new ExponentialBackOffPolicy();
        backoff.setInitialInterval(Math.max(1, encode.getRetryInitialIntervalMs()));
        backoff.setMultiplier(Math.max(1.0, encode.getRetryMultiplier()));
        backoff.setMaxInterval(Math.max(1, encode.getRetryMaxIntervalMs()));

        RetryTemplate retryTemplate = new RetryTemplate();
        retryTemplate.setRetryPolicy(new SimpleRetryPolicy(Math.max(1, encode.getMaxAttempts())));
        retryTemplate.setBackOffPolicy(backoff);

        return RetryInterceptorBuilder.stateless()
                .retryOperations(retryTemplate)
                .recoverer(recoverer)
                .build();
    }

    /**
     * 覆盖 Boot 默认的监听容器工厂，挂上重试 advice 和 JSON 转换器。
     *
     * <p>并发固定为 1：编码服务里的 {@code AtomicBoolean encoding} 是 JVM 级互斥，
     * 同一实例并发消费会让第二个任务直接返回 0，被误判成「已完成」。
     * 跨实例的重复执行由任务 claim 拦住。
     */
    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            @Qualifier("msdsEncodeMessageConverter") MessageConverter messageConverter,
            @Qualifier("msdsEncodeRetryInterceptor") RetryOperationsInterceptor retryInterceptor) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAdviceChain(retryInterceptor);
        factory.setConcurrentConsumers(1);
        factory.setMaxConcurrentConsumers(1);
        factory.setPrefetchCount(1);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
