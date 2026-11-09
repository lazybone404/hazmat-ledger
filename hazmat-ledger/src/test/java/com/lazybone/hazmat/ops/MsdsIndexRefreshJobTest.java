package com.lazybone.hazmat.ops;

import com.lazybone.hazmat.msds.MsdsIndexService;
import com.lazybone.hazmat.msds.MsdsVectorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 刷新任务：版本变了要同时换字面索引与向量快照，没变不碰向量路 */
class MsdsIndexRefreshJobTest {

    private final MsdsIndexService indexService = mock(MsdsIndexService.class);
    private final MsdsVectorService vectorService = mock(MsdsVectorService.class);
    private final MsdsIndexRefreshJob job = new MsdsIndexRefreshJob(indexService, vectorService);

    @Test
    @DisplayName("版本变化时字面索引与向量快照一起刷新")
    void refreshBothWhenVersionChanged() {
        when(indexService.refreshIfVersionChanged()).thenReturn(true);

        job.refreshIfChanged();

        verify(indexService).refreshIfVersionChanged();
        verify(vectorService).refreshVectorIndex();
    }

    @Test
    @DisplayName("版本未变化时不动向量快照")
    void skipVectorWhenVersionUnchanged() {
        when(indexService.refreshIfVersionChanged()).thenReturn(false);

        job.refreshIfChanged();

        verify(vectorService, never()).refreshVectorIndex();
    }

    @Test
    @DisplayName("刷新抛异常只记日志，不向外传播")
    void swallowException() {
        when(indexService.refreshIfVersionChanged()).thenThrow(new IllegalStateException("db down"));

        job.refreshIfChanged();

        verify(vectorService, never()).refreshVectorIndex();
    }

    @Test
    @DisplayName("向量刷新失败同样不向外传播")
    void swallowVectorFailure() {
        when(indexService.refreshIfVersionChanged()).thenReturn(true);
        doThrow(new IllegalStateException("vector down")).when(vectorService).refreshVectorIndex();

        job.refreshIfChanged();
    }
}
