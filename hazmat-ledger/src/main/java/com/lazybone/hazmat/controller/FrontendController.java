package com.lazybone.hazmat.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

import java.nio.charset.StandardCharsets;

/**
 * 演示前端的入口页。
 *
 * <p>为 {@code index.html} 显式加 {@code Cache-Control: no-cache}：每次打开页面都向服务端
 * 确认一次，避免演示时浏览器仍拿着旧版本页面、而 API 已经换了字段。
 * {@code app.js} / {@code styles.css} 用 {@code ?v=} 查询串区分版本，可以长缓存。
 *
 * <p>用控制器而不是静态资源处理器，是因为通配的静态资源规则很难只给 HTML 设缓存头；
 * 这里只接管两个路径，其余静态文件仍由 Spring Boot 默认的 classpath:/static/ 提供。
 * 页面本身不需要登录，但它调用的 {@code /api/**} 仍然逐个要求 Bearer token。
 */
@Controller
public class FrontendController {

    private final Resource index = new ClassPathResource("static/index.html");

    @GetMapping({"/", "/index.html"})
    public ResponseEntity<Resource> index() {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noCache())
                .contentType(new MediaType(MediaType.TEXT_HTML, StandardCharsets.UTF_8))
                .body(index);
    }
}
