package com.lazybone.hazmat.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 登录用户。
 *
 * 业务表里的 applicant / reviewer / operator 仍存**用户名**而不是外键，
 * 原因是台账是审计记录：用户即使被停用，签名也要能读出来。用户名唯一且不随显示名变化，
 * 用作签名比自增 id 更可读，也比「姓名」稳定（姓名可以改、可以重名）。
 *
 * 密码只存 BCrypt 哈希，不存明文，也不做可逆加密——校验只需要「同一密码哈希一致」，
 * 不需要还原。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "app_user",
        indexes = @Index(name = "idx_app_user_role", columnList = "role"))
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 登录名，同时作为台账里的操作人签名 */
    @Column(nullable = false, unique = true, length = 100)
    private String username;

    /** BCrypt 哈希，长度足够容纳算法前缀与 salt */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 界面展示用，允许修改与重名，不参与任何权限判断 */
    @Column(nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserRole role;

    /**
     * 停用而不是删除：台账里的签名还要能追溯。
     * 停用后登录被拒，已签发的 token 在过期前仍然有效——这是无状态 JWT 的已知代价，
     * 文档里写清楚，真要即时踢下线需要引入黑名单，本项目不做。
     */
    @Builder.Default
    @Column(nullable = false)
    private boolean enabled = true;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
