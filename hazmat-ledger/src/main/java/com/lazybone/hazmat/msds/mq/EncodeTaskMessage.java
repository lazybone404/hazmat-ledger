package com.lazybone.hazmat.msds.mq;

/**
 * 编码任务消息体。
 *
 * <p>只带 taskId，不带块内容或版本号：任务表才是事实源。
 * 消费端回表读到的状态永远比消息里携带的快照新，重试和版本切换都靠数据库判断。
 */
public record EncodeTaskMessage(Long taskId) {

    /** 死信 recoverer 在消息体无法反序列化时，靠这个 header 找到任务 */
    public static final String TASK_ID_HEADER = "msdsEncodeTaskId";
}
