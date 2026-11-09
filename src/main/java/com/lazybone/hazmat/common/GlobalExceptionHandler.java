package com.lazybone.hazmat.common;

import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理。处理方式按「调用方能不能自己解决」区分：
 *
 * - 参数校验失败 → 400，把字段级原因带回去（用户能自己改）
 * - 业务规则不让做 → 400，把原因带回去（用户能自己改）
 * - 未登录 / 凭证错误 → 401（换 token 或重新登录）
 * - 已登录但权限不够 → 403（改参数没用，要找管理员）
 * - 资源不存在 → 404（改参数也没用）
 * - 其它 → 500，**只记日志、不把堆栈发给前端**（可能含内部路径和 SQL 片段）
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, detail));
    }

    /**
     * 路径变量与查询参数的校验失败。
     *
     * 请求体校验失败抛的是 {@link MethodArgumentNotValidException}，而
     * {@code @PathVariable} / {@code @RequestParam} 上的约束（需要类上加
     * {@code @Validated}）抛的是这个异常。两者都是调用方传错了参数，必须返回 400，
     * 不处理就会落到兜底分支变成 500。
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiResponse<Void>> handleConstraintViolation(ConstraintViolationException e) {
        String detail = e.getConstraintViolations().stream()
                .findFirst()
                .map(v -> v.getPropertyPath() + " " + v.getMessage())
                .orElse("参数校验失败");
        log.warn("参数校验失败: {}", detail);
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, detail));
    }

    /**
     * 查询参数类型转换失败：{@code page=abc}、{@code status=不存在}、
     * {@code from=2026-13-01} 都会走到这里。
     *
     * 这些是调用方传错了值，不是服务器故障。不单独处理会落到兜底分支变成 500，
     * 排查时容易误以为是代码问题。
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        log.warn("参数类型不匹配: {}", e.getName());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "参数 " + e.getName() + " 的取值不合法"));
    }

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException e) {
        log.warn("业务规则拒绝: {}", e.getMessage());
        return ResponseEntity.badRequest().body(ApiResponse.fail(400, e.getMessage()));
    }

    /**
     * 资源暂时不够用（报表超时、检索并发已满）。返回 503 而不是 500：
     * 监控不该把它算成服务故障，调用方也应该退避重试而不是当成代码 bug。
     */
    @ExceptionHandler(ServiceUnavailableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnavailable(ServiceUnavailableException e) {
        log.warn("服务暂不可用: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(ApiResponse.fail(503, e.getMessage()));
    }

    /**
     * 数据状态在执行时已经变化。返回 409 而不是 400：请求内容没有错，
     * 调用方刷新后重试即可（典型场景：报废审批时该批次余量已被别的单据扣走）。
     */
    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ApiResponse<Void>> handleConflict(ConflictException e) {
        log.warn("数据状态冲突: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(409, e.getMessage()));
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException e) {
        log.warn("资源不存在: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(404, e.getMessage()));
    }

    /**
     * 请求体不是合法 JSON（或字段类型对不上）是调用方错误，应该 400。
     *
     * 不处理的话会落到兜底的 {@code Exception} 分支变成 500，日志里只看到一堆堆栈，
     * 调用方也拿不到可行动的提示。中文被错误编码后发送时，就是这个异常。
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadableBody(HttpMessageNotReadableException e) {
        log.warn("请求体无法解析: {}", e.getMessage());
        return ResponseEntity.badRequest()
                .body(ApiResponse.fail(400, "请求体不是合法的 JSON，或字段类型不匹配"));
    }

    /**
     * 路径没有对应的接口。
     *
     * Spring 6.1 起，未匹配到 Controller 也不再静默落到静态资源，而是抛
     * {@link NoResourceFoundException}。不单独接住的话会被兜底的 {@code Exception}
     * 分支译成 500——访问一个拼错的 URL 却报「服务器内部错误」，排查时会误导人。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoResource(NoResourceFoundException e) {
        log.warn("接口不存在: {} {}", e.getHttpMethod(), e.getResourcePath());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.fail(404, "接口不存在"));
    }

    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnauthorized(UnauthorizedException e) {
        log.warn("认证失败: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(ApiResponse.fail(401, e.getMessage()));
    }

    /**
     * 两类 403 都落在这里：Controller 里主动抛的 {@link ForbiddenException}，
     * 以及 {@code @PreAuthorize} 拒绝时抛出的 {@code AuthorizationDeniedException}
     * （它是 {@link AccessDeniedException} 的子类）。
     *
     * 方法级权限异常发生在 DispatcherServlet 内，会先被这个 advice 接住，
     * 走不到 Security 的 AccessDeniedHandler；两处都配才能保证 404/405 之外的
     * 权限拒绝都返回统一结构。
     */
    @ExceptionHandler({ForbiddenException.class, AccessDeniedException.class})
    public ResponseEntity<ApiResponse<Void>> handleForbidden(RuntimeException e) {
        log.warn("权限不足: {}", e.getMessage());
        String message = e instanceof ForbiddenException ? e.getMessage() : "当前账号无权执行该操作";
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(ApiResponse.fail(403, message));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleOther(Exception e) {
        log.error("未预期的异常", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ApiResponse.fail(500, "服务器内部错误"));
    }
}
