package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.common.ConflictException;
import com.lazybone.hazmat.repository.MsdsIndexLockRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 另一个实例正在重建时，等待超时要翻译成业务冲突，而不是把数据库超时异常抛给调用方。
 *
 * <p>单独一个上下文只为一件事：{@code LOCK_TIMEOUT=1000}。把它和「排队后成功」的用例
 * 放进同一个库做不到——H2 的超时是连接级配置，一个上下文只能有一个值。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:msds-index-lock-short;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=1000",
        "hazmat.ai.encode-on-startup=false",
        "hazmat.jobs.enabled=false"
})
class MsdsIndexLockConflictTest {

    @Autowired private MsdsIndexService indexService;
    @Autowired private MsdsIndexLockRepository lockRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("锁被占住超过等待上限：抛出可重试的业务冲突")
    void rebuildWhileAnotherInstanceHoldsTheLockFailsFast() throws Exception {
        TransactionTemplate holder = new TransactionTemplate(transactionManager);
        CountDownLatch acquired = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<?> holding = pool.submit(() -> holder.executeWithoutResult(status -> {
                // 与 acquireDatabaseLock() 相同：锁住 msds_index_lock 的 id=1 行
                lockRepository.findForUpdate().orElseThrow();
                acquired.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(acquired.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> indexService.rebuild())
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("另一个实例正在重建索引");

            release.countDown();
            holding.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
