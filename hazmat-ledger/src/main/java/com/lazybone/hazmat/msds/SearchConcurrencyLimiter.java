package com.lazybone.hazmat.msds;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * MSDS 检索的并发闸门。
 *
 * <b>为什么只限制检索：</b>融合检索要在内存里遍历全部块做字面打分、
 * 再做一次向量扫描与 RRF 合并，是纯 CPU 的工作，而且单次请求的耗时随着
 * 库变大而增长。这类请求一旦并发堆起来，会把 CPU 占满，连带拖慢台账读写——
 * 而台账是主业务，检索是辅助功能。宁可让检索排队或快速失败，也不让它挤掉主流程。
 *
 * <b>为什么不用 Hikari / Tomcat 的线程数兜底：</b>那些是全局限流，
 * 到那时整个服务都慢了；这里在功能粒度上先设一道闸，检索自身的热点不会外溢。
 *
 * <b>为什么是 Semaphore 而不是信号量式的计数器：</b>许可必须保证释放，
 * Semaphore 的 try/finally 语义最直白，也不需要自己维护计数与并发的正确性。
 *
 * <b>上限怎么定的：</b>检索几乎全是 CPU 工作，按核数给就够——给多了只会增加上下文切换，
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
