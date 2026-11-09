package com.lazybone.hazmat.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务单号：前缀 + 日期 + 8 位十六进制序号，例如 {@code REQ20261106A1B2C3D4}。
 *
 * <p>每个 prefix 在进程内缓存一个号段，用完再向数据库申请下一段。这样建单路径
 * 大多数时候没有额外的数据库往返，多实例之间也不会从同一位置起算。
 *
 * <p>号段允许跳号：分配后业务事务回滚、或进程重启丢弃当前段，都会留下空号。
 * 单号只要求唯一、可读，不要求连续。
 */
@Slf4j
@Service
public class DocumentNoService {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final DocumentNoSegmentAllocator allocator;
    private final int blockSize;
    private final ConcurrentHashMap<String, Block> blocks = new ConcurrentHashMap<>();

    public DocumentNoService(DocumentNoSegmentAllocator allocator,
                             @Value("${hazmat.document-no.block-size:100}") int blockSize) {
        if (blockSize < 1) {
            throw new IllegalArgumentException("hazmat.document-no.block-size 必须大于 0");
        }
        this.allocator = allocator;
        this.blockSize = blockSize;
    }

    public String next(String prefix) {
        while (true) {
            Block block = blocks.get(prefix);
            if (block != null) {
                long value = block.next.getAndIncrement();
                if (value < block.end) {
                    return format(prefix, value);
                }
                // 这一段用完了。只移除自己看到的那一段，避免把别的线程刚换上的新段删掉
                blocks.remove(prefix, block);
                continue;
            }

            long start = allocator.allocate(prefix, blockSize);
            Block fresh = new Block(new AtomicLong(start), start + blockSize);
            if (blocks.putIfAbsent(prefix, fresh) == null) {
                log.debug("启用新号段 {} [{}, {})", prefix, start, fresh.end);
                return format(prefix, fresh.next.getAndIncrement());
            }
            // 另一个线程抢先装了新段。刚申请的段作废，跳过的号可接受
        }
    }

    /**
     * 丢弃本地号段缓存。建单遇到单号唯一冲突时调用：库被恢复、有人手工插过单号后，
     * 本地缓存可能持续撞同一段，不丢弃就只能重启进程。
     */
    public void invalidate(String prefix) {
        blocks.remove(prefix);
    }

    private String format(String prefix, long value) {
        // 单号格式固定 8 位十六进制；序号超过 32 位后回绕，唯一性仍由数据库号段保证
        return prefix + LocalDate.now().format(DATE) + String.format("%08X", value & 0xFFFF_FFFFL);
    }

    private static final class Block {
        private final AtomicLong next;
        private final long end;

        private Block(AtomicLong next, long end) {
            this.next = next;
            this.end = end;
        }
    }
}
