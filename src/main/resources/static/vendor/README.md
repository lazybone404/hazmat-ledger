# 本地前端运行时

演示前端不引入 Node/Vite 构建链，Vue 运行时按版本固化后随 jar 一起发布。

| 项 | 值 |
|---|---|
| 组件 | Vue 3 全局构建（含模板编译器） |
| 版本 | 3.5.45 |
| 文件 | `vue.global.prod.js` |
| 大小 | 172939 字节 |
| SHA-256 | `0287592e25a077b6190b721db9903d253d936778dab50cbde0d6010c1f51d262` |
| 下载日期 | 2026-11-09 |
| 来源 | `https://registry.npmmirror.com/vue/-/vue-3.5.45.tgz`（npmmirror 镜像） |

## 为什么放仓库

演示环境可能没有外网，从 CDN 引用会让「打开页面」依赖第三方可用性。文件只有 169 KB，
放进仓库后运行时不再有网络依赖，版本也固定，不会某天被上游改动。

## 核对方式

```bash
sha256sum src/main/resources/static/vendor/vue.global.prod.js
```

结果应与上表一致。升级版本时同时更新本文件与 `index.html` 里的 `?v=` 查询串。
