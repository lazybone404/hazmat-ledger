package com.lazybone.hazmat.entity;

/**
 * 用户角色。三档就够，不再细分：
 *
 * <ul>
 *   <li>{@link #USER}：提交领用申请、查看与撤回自己的申请；</li>
 *   <li>{@link #APPROVER}：审批申请、查看他人申请；</li>
 *   <li>{@link #ADMIN}：档案与库存写操作、出库核销、MSDS 索引管理。</li>
 * </ul>
 *
 * 没有做「库管员」独立角色，出库核销归 ADMIN。多一档就要多一套测试与文档，
 * 而当前业务里没有「能出库但不能建档」的岗位，先不凭空造。
 */
public enum UserRole {
    USER,
    APPROVER,
    ADMIN
}
