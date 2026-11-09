package com.lazybone.hazmat.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 全局调度开关。
 *
 * <p>原先 {@code @EnableScheduling} 挂在只随 {@code hazmat.mq.enabled=true} 生效的
 * Rabbit 配置上，MQ 关闭时运维任务也一起没有调度能力。这里提出来，让调度本身
 * 与 MQ 解耦；具体任务 Bean 再用 {@code hazmat.jobs.enabled} 控制是否装配，
 * 避免测试上下文里的后台线程和断言抢执行。
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
