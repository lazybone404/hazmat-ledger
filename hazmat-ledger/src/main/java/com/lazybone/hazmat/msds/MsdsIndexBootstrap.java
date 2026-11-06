package com.lazybone.hazmat.msds;

import com.lazybone.hazmat.msds.ai.AiProperties;
import com.lazybone.hazmat.msds.mq.EncodeTaskDispatcher;
import com.lazybone.hazmat.repository.MsdsChunkRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动时保证索引可用。
 *
 * 单独放一个组件而不是在 {@link MsdsIndexService} 上挂监听方法：
 * 重建的事务边界（锁 + TransactionTemplate）属于索引服务本身，启动钩子只负责「什么时候做」，
 * 两者分开后，手动 reindex 与启动重建走的是同一条带事务的路径。
 *
 * 用 {@link ApplicationReadyEvent} 而不是 {@code @PostConstruct}：种子数据由
 * CommandLineRunner 灌入，事件在它之后触发，切块时才能把 chemicalId 关联上。
 */
@Component
public class MsdsIndexBootstrap {

    private static final Logger log = LoggerFactory.getLogger(MsdsIndexBootstrap.class);

    private final MsdsIndexService indexService;
    private final MsdsVectorService vectorService;
    private final MsdsChunkRepository chunkRepository;
    private final EncodeTaskDispatcher encodeTaskDispatcher;
    private final AiProperties aiProperties;

    public MsdsIndexBootstrap(MsdsIndexService indexService,
                              MsdsVectorService vectorService,
                              MsdsChunkRepository chunkRepository,
                              EncodeTaskDispatcher encodeTaskDispatcher,
                              AiProperties aiProperties) {
        this.indexService = indexService;
        this.vectorService = vectorService;
        this.chunkRepository = chunkRepository;
        this.encodeTaskDispatcher = encodeTaskDispatcher;
        this.aiProperties = aiProperties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        try {
            if (chunkRepository.count() == 0) {
                indexService.rebuild();
            } else {
                // 磁盘上的文件可能变过，但重启时先让服务可用，重建交给手动接口
                indexService.refreshFromDatabase();
            }
            // 先按库里已有的向量建快照，检索在编码完成前也能用上旧向量；
            // 编码在后台线程里跑，不阻塞启动，结束后会再刷新一次快照
            vectorService.refreshVectorIndex();
            if (aiProperties.isEncodeOnStartup()) {
                encodeTaskDispatcher.requestEncoding(vectorService.activeVersion(), "startup");
            }
        } catch (RuntimeException e) {
            // 索引失败不应该拖垮整个台账系统：检索退化为空结果，其他功能照常
            log.error("MSDS 索引初始化失败，检索功能将不可用", e);
        }
    }
}
