package com.lazybone.hazmat.common;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 业务单号生成：前缀 + 日期 + 8 位十六进制序号，例如 {@code REQ20261106A1B2C3D4}。
 *
 * <p><b>为什么不用「当天最大号 + 1」。</b>那要查一次库，并发下还得加锁或重试，
 * 单号又会成为写路径上的串行点。单号只用于人眼识别和口头沟通，不要求连续。
 *
 * <p><b>为什么不用纯随机。</b>最初是「日期 + 4 位随机数」，写路径压测里
 * 734 次并发建单撞了 72 次唯一索引（10 位数字空间，生日悖论），撞一次就是一个 500。
 * 改成进程内的自增序号：同一 JVM 内确定性唯一（2^32 次内不重复，按每次建单算也够用几百年），
 * 不查库、不加锁。启动时用随机数播种，避免多实例从同一位置起算。
 *
 * <p><b>边界。</b>多实例部署时各自从随机位置自增，理论上仍可能撞号，
 * 概率约 n²/2³³（每天 1 万单约 1%）；唯一索引仍是最后一道防线，
 * 调用方必须把唯一约束冲突翻译成可重试的业务错误，而不是让它变成 500。
 */
public final class DocumentNoGenerator {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 起点随机，避免多个实例从同一序号开始 */
    private static final AtomicLong SEQUENCE =
            new AtomicLong(ThreadLocalRandom.current().nextLong(1L << 32));

    private DocumentNoGenerator() {
    }

    public static String next(String prefix) {
        long value = SEQUENCE.incrementAndGet() & 0xFFFF_FFFFL;
        return prefix + LocalDate.now().format(DATE) + String.format("%08X", value);
    }
}
