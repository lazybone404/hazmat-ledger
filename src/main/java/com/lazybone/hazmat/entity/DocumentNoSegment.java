package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 业务单号号段。
 *
 * <p>每个 prefix 一行，{@code nextValue} 是下一段的起点。分配时锁这一行并把
 * {@code nextValue} 推后一个块，段内由应用进程自增。多实例共享同一行，
 * 不再各自从随机位置起算。
 *
 * <p>号段允许跳号：业务事务回滚时，已经分配给它的号不会退回。单号只要求唯一、
 * 可读，不要求连续。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "document_no_segment")
public class DocumentNoSegment {

    @Id
    @Column(length = 8)
    private String prefix;

    @Column(nullable = false)
    private Long nextValue;

    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
