package com.lazybone.hazmat.msds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * MSDS 检索中**向量路**的并发闸门。
 *
 * <b>为什么只闸向量路：</b>向量检索要么是一次外部 HTTP 调用（云端 embedding），
 * 要么是遍历全部块做余弦扫描，是整条检索链里最重、最容易被下游拖住的一段。
 * 拿不到许可时直接跳过这一路、保留字面结果，比让整个检索返回空更可用——
 * 前端只要照常处理 {@code degraded} 标记，用户仍能看到命中，只是少了语义召回。
 *
 * <b>为什么不把字面路也闸住：</b>字面路是纯内存扫描，没有外部依赖，
 * 也是并发满时的兜底。把它一起挡住，就等于在过载时把检索彻底关掉。
 *
 * <b>为什么不用 Hikari / Tomcat 的线程数兜底：</b>那些是全局限流，
 * 到那时整个服务都慢了；这里在功能粒度上先设一道闸，检索自身的热点不会外溢。
 *
 * <b>为什么是 Semaphore 而不是信号量式的计数器：</b>许可必须保证释放，
 * Semaphore 的 try/finally 语义最直白，也不需要自己维护计数与并发的正确性。
 *
 * <b>上限怎么定的：</b>向量路几乎全是 CPU 工作，按核数给就够——给多了只会增加上下文切换，
 * 不会提高吞吐。默认 4 是保守值，容器核数少时应调小，而不是照抄。
 */
@Component
public class SearchConcurrencyLimiter {

    private final Semaphore permits;
    private final long waitMillis;

    public SearchConcurrencyLimiter(
            @Value("${hazmat.msds.search.max-concurrent:4}") int maxConcurrent,
            @Value("${hazmat.msds.search.wait-millis:200}") long waitMillis) {
        // 配置写错（0 或负数）时 Semaphore 会直接抛 IllegalArgumentException；
        // 这里给个下限，让配置问题表现为「少排队」而不是启动失败
        this.permits = new Semaphore(Math.max(1, maxConcurrent), true);
        this.waitMillis = Math.max(0, waitMillis);
    }

    /**
     * 尝试占用一个许可。
     *
     * 短暂等待（默认 200ms）而不是立刻拒绝：正常的突发请求里，前一个请求通常已经快结束了，
     * 等一小会儿比让用户重试体验更好。等待时间必须短——它是同步阻塞 Tomcat 工作线程的，
     * 等太久等于把全局限流的问题换了个位置。
     */
    public boolean tryAcquire() {
        try {
            return permits.tryAcquire(waitMillis, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** 必须在 finally 里调用，否则许可泄漏，检索会在跑过一次高并发后永久降级 */
    public void release() {
        permits.release();
    }

    /** 当前空闲许可数，仅用于测试与排查 */
    int availablePermits() {
        return permits.availablePermits();
    }
}
