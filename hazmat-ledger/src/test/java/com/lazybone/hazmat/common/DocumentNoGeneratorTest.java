package com.lazybone.hazmat.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentNoGeneratorTest {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Test
    @DisplayName("并发生成 10 万个单号不重复")
    void concurrentNumbersAreUnique() {
        int total = 100_000;
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger duplicates = new AtomicInteger();

        IntStream.range(0, total).parallel().forEach(i -> {
            if (!seen.add(DocumentNoGenerator.next("REQ"))) {
                duplicates.incrementAndGet();
            }
        });

        assertThat(duplicates.get()).as("单号重复次数").isZero();
        assertThat(seen).hasSize(total);
    }

    @Test
    @DisplayName("格式是「前缀 + 日期 + 8 位十六进制」，人眼可直接读")
    void formatIsReadableAndDated() {
        String no = DocumentNoGenerator.next("ST");

        String today = LocalDate.now().format(DATE);
        assertThat(no).matches("ST" + today + "[0-9A-F]{8}");
    }
}
