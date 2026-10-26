package com.lazybone.hazmat.msds.ai;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * MSDS 向量索引的配置。
 *
 * key 只从环境变量来（application.properties 里写的是 {@code ${DASHSCOPE_API_KEY:}}）。
 * 没有 key 或总分开关关闭时，向量由本地哈希算，不联网、不要凭据，功能不挂。
 *
 * 默认关闭云端：MSDS 正文会发送到第三方模型服务，这属于需要显式确认的行为，
 * 不能因为「本地演示方便」就默认开着。
 */
@Data
@Component
@ConfigurationProperties(prefix = "hazmat.ai")
public class AiProperties {

    /** 是否允许调用云端模型。false 时只用本地哈希向量 */
    private boolean enabled = false;

    private String baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1";

    private String apiKey = "";

    /** 算向量的模型。换模型必须重新编码，四元组校验会拦住旧向量 */
    private String embeddingModel = "text-embedding-v4";

    private int embeddingDimensions = 512;

    /** 单次调用超时（毫秒）。超时即记一次失败，不让后台线程挂住 */
    private int timeoutMs = 3000;

    /** 单次请求条数。百炼同类接口上限是 10，配大了接口会直接报错 */
    private int batchSize = 10;

    /**
     * 向量召回的余弦下限，低于它的结果在向量路里丢掉。
     *
     * 0.0 表示「不过滤」：这个数必须在本项目数据与当前 embedding 模型上用题集标定后才有意义，
     * 语料、模型、切块一变，分数分布就整体平移，从别处抄来的阈值会同时造成漏召回和误召回。
     * 标定方法见 docs/msds-rag-设计.md 的 8.3。
     */
    private double minScore = 0.0;

    /** 启动后是否自动编码待处理块。关闭后只能通过 reindex 接口触发 */
    private boolean encodeOnStartup = true;

    /** 本轮连续失败多少块后暂停，避免 key 失效时把整库都标成失败 */
    private int maxConsecutiveFailures = 3;

    /** 单块累计失败多少次后不再重试。持久化在块上，重启后仍然生效 */
    private int maxFailuresPerChunk = 3;

    /** 有 key 且开关打开才走云端 */
    public boolean hasApiKey() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
