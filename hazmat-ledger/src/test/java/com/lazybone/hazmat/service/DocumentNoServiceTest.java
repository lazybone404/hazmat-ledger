package com.lazybone.hazmat.service;

import com.lazybone.hazmat.entity.DocumentNoSegment;
import com.lazybone.hazmat.repository.DocumentNoSegmentRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 号段分配。用独立的 TST 前缀，避免和其它测试的 REQ/ST 互相影响。
 */
@SpringBootTest
class DocumentNoServiceTest {

    private static final int BLOCK = 100;

    @Autowired
    private DocumentNoSegmentAllocator allocator;

    @Autowired
    private DocumentNoSegmentRepository repository;

    @Test
    @DisplayName("同一实例并发取号不重复")
    void concurrentSameInstanceDoesNotDuplicate() throws Exception {
        String prefix = "TSTC";
        repository.deleteById(prefix);
        try {
            DocumentNoService service = new DocumentNoService(allocator, BLOCK);
            Set<String> numbers = ConcurrentHashMap.newKeySet();
            int threads = 8;
            int perThread = 200;

            ExecutorService pool = Executors.newFixedThreadPool(threads);
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    for (int j = 0; j < perThread; j++) {
                        String no = service.next(prefix);
                        assertTrue(numbers.add(no), "出现重复单号: " + no);
                    }
                }));
            }
            for (var future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(threads * perThread, numbers.size());
        } finally {
            repository.deleteById(prefix);
        }
    }

    @Test
    @DisplayName("两个应用实例共享数据库，各自取号不重复")
    void twoInstancesDoNotDuplicate() throws Exception {
        String prefix = "TSTT";
        repository.deleteById(prefix);
        try {
            DocumentNoService instanceA = new DocumentNoService(allocator, BLOCK);
            DocumentNoService instanceB = new DocumentNoService(allocator, BLOCK);
            Set<String> numbers = ConcurrentHashMap.newKeySet();
            int perInstance = 500;

            ExecutorService pool = Executors.newFixedThreadPool(2);
            var a = pool.submit(() -> {
                for (int i = 0; i < perInstance; i++) {
                    String no = instanceA.next(prefix);
                    assertTrue(numbers.add(no), "实例 A 出现重复单号: " + no);
                }
            });
            var b = pool.submit(() -> {
                for (int i = 0; i < perInstance; i++) {
                    String no = instanceB.next(prefix);
                    assertTrue(numbers.add(no), "实例 B 出现重复单号: " + no);
                }
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
            pool.shutdown();
            assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS));

            assertEquals(perInstance * 2, numbers.size());
        } finally {
            repository.deleteById(prefix);
        }
    }

    @Test
    @DisplayName("数据库号段按块前进")
    void segmentAdvancesByBlock() {
        String prefix = "TSTS";
        repository.deleteById(prefix);
        try {
            DocumentNoService service = new DocumentNoService(allocator, BLOCK);
            service.next(prefix);

            DocumentNoSegment segment = repository.findById(prefix).orElseThrow();
            assertEquals(1L + BLOCK, segment.getNextValue());

            // 当前段耗尽后再申请一段
            for (int i = 1; i < BLOCK; i++) {
                service.next(prefix);
            }
            service.next(prefix);
            assertEquals(1L + BLOCK * 2, repository.findById(prefix).orElseThrow().getNextValue());
        } finally {
            repository.deleteById(prefix);
        }
    }
}
