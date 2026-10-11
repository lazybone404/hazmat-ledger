package com.lazybone.hazmat.common;

/**
 * 统一响应体：{@code code / message / data}。
 * 前端和测试都按这个形状断言，成功与业务失败走同一个结构，
 * 调用方不用按 HTTP 状态码分两套解析逻辑。
 */
public record ApiResponse<T>(int code, String message, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> fail(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
