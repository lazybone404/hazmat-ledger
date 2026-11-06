# hazmat-ledger · 高校实验室危化品领用与台账管理

> Spring Boot 3 的危化品全流程台账（默认 H2，可切 MySQL）。核心是两条业务主线：
> **领用申请 → 审批 → 出库 → 台账**，以及 **盘点 → 调账 → 对账**。
> 权限、分页、报表、过载兜底是支撑能力；知识库（MSDS 安全信息检索）已并入主线，
> 挂在试剂详情的安全入口上；禁忌混放检查用结构化关系表，与在库试剂两两比对，
> 并按批次柜位判断是否真的同柜存放。

这个文件说清「它是什么、怎么跑、代码在哪」，以及关键设计取舍（第 4 节）。

---

## 0. 这个项目讲什么

两条业务主线；知识库与混放检查是旁路功能，检索挂了不影响台账开单。

| 主线 | 链路 | 回答的问题 | 代码位置 |
|---|---|---|---|
| 一 | 领用申请 → 双人审批 → FEFO 出库 → 台账 | 一笔领用怎么走完？并发下怎么不超发、不重复批？ | `RequisitionService`、`StockService#issue` |
| 二 | 建单冻结 → 实盘录入 → 确认调账 → 对账 | 账和实物对不上怎么办？怎么证明账是对的？ | `StocktakeService`、`StockService#reconcile` |

两条主线用同一个收口：**对账**。主线一保证「每一笔都算对了」，主线二负责发现
「有没有漏记的动作」——没有盘点，前者自洽但可能与实物脱节。

支撑能力（不主动展开）：JWT 三档角色、分页与组合查询、只读报表层、过载快速失败、
开封与可用期（并入主线一的出库环节）。

知识库（旁路功能）：从台账任一试剂点进去，按 CAS 号限定检索 MSDS 原文片段；
`/api/msds/ask` 在检索之上作答，每条结论带后端生成的 `[n]` 引用。**引用必须能落回
具体块**：模型输出的编号越界或一个有效引用都没有，整段生成作废，退回原文摘录。
没有模型时只返回带引用的原文片段；检索为空、降级或片段与问题毫无词面对应时
明确返回 `answerable=false`，不硬答。检索失败不影响库存与审批。设计见
[docs/msds-rag-设计.md](docs/msds-rag-设计.md)，取舍依据见
[docs/架构决策.md](docs/架构决策.md)。

禁忌混放检查（旁路功能）：用 `chemical_incompatibility` 关系表，对当前有实物库存的
试剂两两比对，再看批次 `location` 是否有交集。**只有同柜才算违规**；规则命中但分柜
存放的单独返回，提示继续保持分离。命中的组合带出危险原因与 MSDS 出处；**规则表里
没有的组合显式返回 `uncovered`，不代表可以混放**。见
[4.8](#48-旁路禁忌混放未覆盖必须显式)。

---

## 1. 跑起来

```bash
export JAVA_HOME="/c/Program Files/Java/jdk-21.0.11"   # 见下方「环境要求」
./mvnw spring-boot:run        # 端口 8090，启动后自动灌 18 条试剂档案、21 个批次
./mvnw test                   # 189 个测试，不需要任何中间件；E4 对照与 E9 生成评测需外部服务，默认跳过（2 个）
```

演示用的固定日期数据里特意留了**已过期**和**临期**两类批次，所以启动后直接就能看到预警。

### 登录（所有业务接口都要 Bearer token）

启动时会创建 5 个演示账号，密码都取自 `hazmat.security.seed-password`（默认 `ChangeMe123!`）：

| 账号 | 角色 | 能做什么 |
|---|---|---|
| `admin` | ADMIN | 建档、入库、报废、出库、核销、MSDS 索引管理 |
| `approver1` / `approver2` | APPROVER | 审批、查看他人申请 |
| `alice` / `bob` | USER | 提交申请、查看/撤回自己的申请 |

```bash
curl -s -X POST http://localhost:8090/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"alice","password":"ChangeMe123!"}'
# 取返回 data.token，之后每个请求加 -H "Authorization: Bearer <token>"
```

部署前改掉默认密码，或关闭 `hazmat.security.seed-users`。

### 连真实 MySQL

```bash
./mvnw package -DskipTests
java -jar target/hazmat-ledger-0.0.1-SNAPSHOT.jar --spring.profiles.active=mysql
# 或开发时：./mvnw spring-boot:run -Dspring-boot.run.profiles=mysql
```

连接配置在 `src/main/resources/application-mysql.properties`，默认 `root/88888888`（可用 `DB_PASSWORD` 环境变量覆盖），
表结构由 Flyway 的 `db/migration` 脚本创建。MySQL 下如果不想灌种子数据，加 `--hazmat.seed.enabled=false`。

### 容器化部署

```bash
docker compose up -d --build      # app(8090) + mysql(宿主机 3307)
curl http://localhost:8090/actuator/health
docker compose down               # 保留数据卷；加 -v 连数据一起删
```

只想用 H2 跑，不启动 MySQL：

```bash
docker build -t hazmat-ledger:local .
docker run --rm -p 8090:8090 \
  -e HAZMAT_JWT_SECRET=至少32字节的密钥 hazmat-ledger:local
```

镜像分两阶段构建：构建阶段用 Maven + JDK 21 打包，运行阶段只留 JRE，并以非 root 用户启动。
依赖下载走 `docker/maven-settings.xml` 里的国内镜像。`compose.yaml` 里的密码与 JWT 密钥都是演示值，部署到任何公开环境前必须换掉。

### 接口文档与探活

- Swagger UI：`http://localhost:8090/swagger-ui.html`（先调登录接口拿 token，再点 Authorize）
- OpenAPI JSON：`http://localhost:8090/v3/api-docs`
- 健康检查：`http://localhost:8090/actuator/health`，只暴露 `health` / `info`

文档与探活都不需要登录。生产环境用 `SPRINGDOC_ENABLED=false` 关闭文档，或由网关限制在内网。

### 环境要求（踩过）

**必须用 JDK 21 构建**。机器上 PATH 里的默认 `java` 是 JDK 26，而 Lombok 在它上面**静默失效**——
表现是几百个「找不到符号」，全指向 Lombok 生成的 getter，看起来像代码坏了，实际一行没坏。

---

## 2. 接口

| 方法 | 路径 | 权限 | 说明 |
|---|---|---|---|
| POST | `/api/auth/login` | 公开 | 登录，返回 JWT |
| GET | `/api/auth/me` | 登录 | 当前用户信息 |
| POST | `/api/chemicals` | ADMIN | 新建试剂档案（CAS 号唯一）；可选 `postOpenDays` 配置开封后可用天数，不传表示不做开封追踪 |
| GET | `/api/chemicals?keyword=&category=&hazardLevel=&page=&size=&sort=` | 登录 | 试剂分页列表：关键词匹配名称/CAS，分类与危险等级精确筛选 |
| GET | `/api/chemicals/expiring?withinDays=` | 登录 | 效期预警分页：仍有库存且 `withinDays` 天内到**可用截止日**（含已过期），按 `usableUntil` 升序 |
| GET | `/api/chemicals/{id}` | 登录 | 试剂详情（列表只有摘要字段，详情页按 id 取完整档案） |
| GET | `/api/chemicals/{id}/safety?q=&docType=&topK=` | 登录 | **知识库入口**：按该试剂 CAS 限定检索，返回章节号/条款号、原文与降级原因。`docType` 可选 `MSDS`/`GB`/`SOP`。没有索引过该试剂时返回 `available=false` + 明确提示，不返回空列表 |
| GET | `/api/incompatibilities` | 登录 | **混放检查**：返回 `conflicts`（同柜且禁配）、`separatedConflicts`（禁配但分柜）、`uncovered`（规则表没记录，**不代表安全**）；每条带 `sharedLocations` |
| GET | `/api/chemicals/{id}/incompatibilities` | 登录 | 单个试剂的混放检查，含 `locations`、同柜/分柜冲突与未覆盖搭档；无库存时返回 `inStock=false`，不产生虚假冲突 |
| GET | `/api/chemicals/{id}/ledger?page=&size=&sort=` | 登录 | 某试剂的出入库流水分页，默认按发生时间倒序 |
| POST | `/api/chemicals/{id}/batches` | ADMIN | 入库，新建批次 |
| POST | `/api/chemicals/{id}/batches/{batchId}/open` | ADMIN | 开封：记录开封时间与操作人；档案配了 `postOpenDays` 时把可用截止日提前为 `min(包装有效期, 开封日 + 天数)` |
| POST | `/api/chemicals/{id}/batches/{batchId}/scrap` | ADMIN | 报废：从批次扣减不可用量，必须写原因 |
| GET | `/api/chemicals/{id}/stock` | 登录 | 库存视图：批次明细 + 可用量 + 效期预警 |
| GET | `/api/chemicals/{id}/reconcile` | 登录 | 对账：逐批次「批次余额 vs 最后一笔流水余额」，外加汇总量交叉验证，返回 `consistent` |
| POST | `/api/stocktakes` | ADMIN | 建盘点单。不传 `chemicalIds` 为全盘；建单时把每个批次的账面量冻结成快照 |
| GET | `/api/stocktakes?status=&page=&size=&sort=` | 登录 | 盘点单分页列表，默认按创建时间倒序 |
| GET | `/api/stocktakes/{stocktakeNo}` | 登录 | 盘点单详情 + 全部明细（账面 / 实盘 / 差异） |
| PUT | `/api/stocktakes/{stocktakeNo}/items/{itemId}` | ADMIN | 录入某批次实盘量，仅 `DRAFT` 可改；重复录入即覆盖 |
| POST | `/api/stocktakes/{stocktakeNo}/submit` | ADMIN | 提交。任一批次未录入即 400，并列出漏录的批次号 |
| POST | `/api/stocktakes/{stocktakeNo}/confirm` | ADMIN | 确认并调账：差异写成 `ADJUST` 流水，批次余额改为实盘量 |
| POST | `/api/stocktakes/{stocktakeNo}/cancel` | ADMIN | 撤销，不调账。已确认的不能撤销 |
| POST | `/api/requisitions` | 登录 | 提交领用申请，申请人取当前账号 |
| GET | `/api/requisitions/{reqNo}` | 本人 / APPROVER / ADMIN | 申请详情 + 完整流转时间线 |
| GET | `/api/requisitions?applicant=&status=&from=&to=&page=&size=&sort=` | 本人 / APPROVER / ADMIN | 申请分页列表；不传 `applicant` 默认查自己，`from`/`to` 为自然日 |
| POST | `/api/requisitions/{reqNo}/review` | APPROVER / ADMIN | 审批（通过 / 驳回）。`hazardLevel=高` 的试剂要**两位不同审批人**各批一次，且申请人不能批自己的 |
| POST | `/api/requisitions/{reqNo}/issue` | ADMIN | 出库 |
| POST | `/api/requisitions/{reqNo}/close` | ADMIN | 核销 |
| POST | `/api/requisitions/{reqNo}/cancel` | 登录（仅本人） | 撤回 |
| GET | `/api/msds/search?q=&casNo=&docType=&topK=` | 登录 | 检索知识片段（MSDS / 国标 / SOP）。`q` 必填、上限 200 字符，`casNo` 与 `docType` 可选，`topK` 默认 5、上限 20。返回 `MsdsSearchResult`：命中列表（每条带 `sources`、`docType`、`clauseNo`）+ 降级原因 + 索引状态 |
| GET | `/api/msds/ask?q=&casNo=&topK=` | 登录 | 检索并作答。返回 `MsdsAnswer`：`answerable` / `generated` / `answer` / `citations` / `degraded` / `degradedReason`。引用由后端从命中生成，模型只能选编号（最多前 5 条）；无模型或调用失败退回原文摘录（`generated=false`），无依据时 `answerable=false` |
| POST | `/api/msds/reindex` | ADMIN | 扫描 `resources/msds/*.md` 增量落块；四元组变了就作废旧向量，然后在后台开始编码 |
| GET | `/api/msds/status` | ADMIN | 向量索引状态：进度、暂停原因、当前模型、元数据是否需重建 |
| GET | `/api/reports/stock-summary?withinDays=` | 登录 | 库存汇总（MyBatis-Plus）：按分类 + 单位给出试剂数、批次数、总量、临期量与过期量 |
| GET | `/api/reports/flow-trend?days=` | 登录 | 出入库趋势（MyBatis-Plus）：按自然日 + 变更类型汇总，数量带符号 |
| GET | `/actuator/health` | 公开 | 探活，返回 `{"status":"UP"}`；`/actuator/health/liveness`、`/actuator/health/readiness` 同样公开 |
| GET | `/swagger-ui.html`、`/v3/api-docs` | 公开 | 交互式接口文档与 OpenAPI JSON |

身份一律来自 token：申请人、审批人、操作人都不能由请求体或查询参数指定。
没有 token 返回 401，角色不够返回 403，两者都是统一的 `ApiResponse` 结构。

### 分页与查询约定

所有列表接口返回同一种结构，包在 `ApiResponse.data` 里：

```json
{ "content": [], "page": 0, "size": 20, "totalElements": 0, "totalPages": 0, "hasNext": false }
```

- `page` 从 0 开始，`size` 默认 20、**上限 100**；越界一律 400，不会静默截断。
- `sort=字段,asc|desc` 只接受白名单字段（试剂：`id/name/casNo/category/hazardLevel/createdAt`；
  申请：`id/createdAt/status/quantity`；流水：`id/createdAt/quantity/changeType`；
  盘点单：`id/createdAt/status`），其它字段返回 400 并列出可用字段。
- **稳定排序**：无论传什么排序，最后都追加 `id`。只按 `createdAt` 这类可能重复的列排序时，
  两次查询的顺序不保证一致，翻页会出现「同一条出现在两页、另一条消失」。
- 关键词里的 `%`、`_`、`\` 会被转义成普通字符，搜索「50%」不会变成匹配任意内容。
- 时间筛选按自然日：`to=2026-10-27` 含当天 23:59:59 的申请。

### 报表层与 MyBatis-Plus 的边界

项目里有两套 ORM，但**职责是切开的**：

| | JPA（Spring Data） | MyBatis-Plus |
|---|---|---|
| 范围 | 全部写路径、实体、并发控制、分页列表 | 只有 `/api/reports/*` 两个只读报表 |
| SQL | 条件更新、乐观锁由框架生成 | 手写 XML，`GROUP BY` / `CASE WHEN` / `LEFT JOIN` |
| 返回 | 实体 / `PageResponse` | 报表自己的 Row 对象，不映射实体 |

- **不双写**：报表只做 `SELECT`，写操作仍然走 JPA 的条件更新与 `@Version`。两边不会争同一个实体的修改权。
- **不共享实体**：报表用独立的 `StockSummaryRow` / `FlowTrendRow`，避免同一张表出现两套字段映射——改一列时只改一边，另一边悄悄错掉。
- **报表不分页**：结果行数是「分类 × 单位」的组合数，量级十几条；分页只会增加前端复杂度。若某个报表行数会随数据量增长，必须改回 `PageResponse`。
- **为什么这层用 MyBatis 而不是 JPA 原生查询**：聚合 SQL 在 XML 里是完整一条，可读、可单独执行、可直接贴进数据库客户端核对；JPA 原生查询会把它拆成字符串常量 + 投影接口两半。
- **代价**：项目多一套 ORM 的心智负担；Flyway 的 `validate` 只校验 JPA 实体，报表 SQL 的列名正确性只能靠测试与真实库验证；两套 SQL 风格并存。因此**不要因为「MyBatis 方便」就把写路径迁移过去**——并发控制与事务语义已经按 JPA 的方式验证过。
- 配置在 `application.properties`：`mybatis-plus.mapper-locations` 指向 `resources/mapper/*.xml`，`map-underscore-to-camel-case=true` 负责 `chemical_count → chemicalCount` 的映射。

---

## 3. 状态机（主线一）

```
PENDING ──审批通过（普通试剂，一人）──────────> APPROVED ──出库──> ISSUED ──核销──> CLOSED
   │  └──第一人通过（高危试剂，双人）──> PENDING_SECOND ──第二人通过──┘
   │                                        │
   ├──审批驳回──> REJECTED <─────审批驳回────┘
   └──本人撤回──> CANCELLED <──本人撤回──┘（APPROVED 也能撤回）
```

流转规则写在 `RequisitionStatus` 枚举里（`canTransitionTo`），不散在 Service 的 if 里：
改规则只有一处，测试也能直接对着它写。`REJECTED` / `CLOSED` / `CANCELLED` 是终态。

**双人复核**（`hazardLevel=高` 的试剂，法规依据是《高等学校实验室安全规范》第二十五条的「五双」）：
第一位审批人通过后落到中间态 `PENDING_SECOND`，还要**另一位**审批人再通过一次才到 `APPROVED`。
任一人驳回即驳回。设计取舍见 4.4。

注意枚举只表达「状态到状态」合不合法；「两个审批人必须是不同的人」取决于上一个审批人是谁，
是数据不是状态，所以由 Service 判（比对 `Requisition.firstReviewer`）。

盘点单的状态机同样写在枚举里（`StocktakeStatus.canTransitionTo`）：

```
DRAFT ──提交（要求全部实盘量已录）──> SUBMITTED ──确认并调账──> CONFIRMED
  │                                      │
  └────────────撤销────────┬─────────────┘
                           └────────────> CANCELLED
```

`CONFIRMED` / `CANCELLED` 是终态：防止「已取消的单又被确认」或重复确认把同一笔差异调两次。
`DRAFT` 与 `SUBMITTED` 分开，是因为「录入」和「确认」是两个动作——合成一步的话，
手滑输错一个 0 就直接改进台账了。

---

## 4. 设计取舍（按主线标注）

小节标题后的括号说明它服务于哪条主线；标「支撑」的不主动展开。

### 4.1 （支撑）缓存只放档案，不碰账

**先压测，再决定加不加。** 这个判断不是拍脑袋来的：H2 装载 1 万试剂 / 10 万批次 / 110 万流水，
32 并发下分页列表、库存、流水、对账、申请列表都在 4300~5200 QPS、p95 20 ms 以内；
并发提到 100 时 QPS 不再上升（4099 → 4059 → 4248），延迟线性增长——瓶颈是连接池与 CPU，
不是缺缓存。真正随数据量线性退化的只有两处：

- **关键词 `LIKE '%x%'` 搜索**：命中行数随数据量涨，同样条件下 5 倍数据从 1845 掉到 521 QPS；
- **报表全表聚合**：扫描行数随数据量涨，22 万流水 119.6 QPS → 110 万流水 22.4 QPS（p50 从 265 ms 涨到 1515 ms）。

这两处该用全文索引、预聚合或异步导出解决，缓存结果只会把「账不是最新的」引进来——
而台账系统里，账的时效性恰恰不能打折。

**所以缓存只加在一处：试剂档案。** 边界是按「陈旧有没有害」切的：

| 对象 | 缓存 | 理由 |
|---|---|---|
| `chemical` 档案 | ✅ 30 分钟 + 随机抖动 | 读多写少，CAS 号不会因为缓存半小时就变错；接口只有建档与查询、**没有修改入口**，缓存不需要主动失效 |
| 批次库存、流水、报表、盘点基准 | ❌ | 这些是账，任何一秒的陈旧都等于错账。缓存引入的正是「Redis 扣了数据库没扣」这类一致性问题，不引入就不会有 |

扣减与状态变更仍然全部走数据库的条件更新与版本号，不经过缓存——缓存命中与否不改变任何
一次扣减的结果。

**降级是一等公民。** Redis 连不上时直接查库，接口照常返回；写缓存失败只记日志，不影响
已提交的写入。超时配 1 秒，`management.health.redis.enabled=false` 必须关——否则 Redis
挂掉会让 `/actuator/health` 整体 DOWN，把一个可降级的依赖升级成不可用。

**空值哨兵**（防穿透）与**随机 TTL**（防雪崩）都做了；**击穿互斥没做**——回源是一次主键
点查，为它加 SETNX 要多两次网络往返，不值。这是取舍，不是遗漏。

压测还顺带查出一个 O(n) 的批次号查重，已改成唯一索引点查
（同一试剂连续入库从 114 QPS 恢复到 3170 QPS）。

### 4.2 （主线一、二）两种并发手法，用在不同地方

```java
// 扣库存：不需要读，条件更新一步到位
UPDATE chemical_batch SET available_quantity = available_quantity - :qty
 WHERE id = :id AND available_quantity >= :qty      -- 判断和扣减在同一条语句里

// 改状态：必须先读出来看是不是 PENDING，才能决定能不能批 → 版本号乐观锁
@Version private Long version;
```

判断依据是**这次更新需不需要先读**。不需要读就别读，多一次往返还拉长冲突窗口。
详见 `ChemicalBatchRepository.deduct` 和 `Requisition` 上的注释。

### 4.3 （支撑）其它几个刻意的选择

- **档案表与批次表分开**：同一试剂分多次采购、每批效期不同，台账要能追溯到具体那一瓶。
- **`BigDecimal` 不用 `double`**：0.001 mL 是日常精度，double 表示不了 0.1，累加几十次就对不上账。
- **流水表存 `balanceAfter`**：对账从「累加全部历史」变成「比最后一笔」，而且漏记一笔立刻能看出来。
- **出库按效期升序但不含过期**：按效期是为了减少浪费；过期是报废不是临期，必须过滤掉。
- **单号用日期 + 随机数**：避开了「查今天最大号 + 1」这个额外并发点，唯一性交给唯一索引兜底。

### 4.4 （主线一）双人复核：加一个状态，而不是加一个计数

高危试剂要两人复核。**为什么不是「留在 PENDING、另外记一个已批次数」**：

- 停在 `PENDING` 的话，第二次审批和第一次审批从状态上看一模一样，「还差几个人」这条规则就只能
  漏到 Service 的 if 里，和「流转规则只此一处」的约定打架；
- 中间态 `PENDING_SECOND` 顺带把「只批一次就出库」也挡住了——它没有通向 `ISSUED` 的边；
- 留痕表不用加字段就能区分第一人和第二人：第一人写 `PENDING → PENDING_SECOND`，
  第二人写 `PENDING_SECOND → APPROVED`，语义由状态机赋予。

**「同一个人不能凑两次数」落在 Service**（比对 `firstReviewer`），因为它是数据依赖而不是状态转移。
只统计次数的实现在「一个人点两次」时会放行——那等于没做双人。

**另一个取舍：审批撞上乐观锁冲突时是重试，不是报错。** 两个人同时点通过时，版本号会拦下后写的那个，
但**后写的那个恰恰是流程还缺的第二票**，不能丢。所以 `RequisitionService.review` 在冲突时
换一个新事务重读重判（重读会看到 `PENDING_SECOND`，这一票正好成为第二票）。
事务边界因此由 `TransactionTemplate` 显式控制——同一个事务里抛过乐观锁异常之后持久化上下文
已经不可用，重试必须在新事务里做。
对照扣库存：那里冲突的一方**可以**放弃（货被别人领走了，他本来也领不到），所以条件更新返回 0 跳过就行。

### 4.5 （支撑）过载时快速失败，而不是排队

正常流量下这套配置一行也不会被执行到；它的价值在于**流量突然翻十倍时，服务是变慢还是变死**。
原则只有一条：每一层资源都有上限，拿不到资源就在有限时间内失败，不能无限排队。

| 层 | 上限 | 拿不到时 |
|---|---|---|
| Hikari 连接池 | 10 个连接，等 3 秒 | 抛 `SQLTransientConnectionException` → **503** |
| Tomcat 工作线程 | 100 线程 + 50 排队 | 超过直接拒绝连接 |
| 报表 SQL | `Statement` 超时 5 秒 | `SQLTimeoutException` → **503** |
| MSDS 向量路 | 4 个并发许可，等 200ms | 跳过向量路，字面结果照常返回，`degraded=true` + 原因；**不返回空列表** |
| 登录 | 同一用户名 5 次失败 | 锁 1 分钟，锁定期连 BCrypt 都不跑 |

几个决定背后的原因：

- **排队比失败更危险**。请求排队不会消失，只是从「几个请求慢」变成「所有请求都超时」，
  而排队堆的是线程和连接；等到线程栈和连接池都被占满，进程往往不是变慢而是被 OOM 或健康检查拖死。
- **503 而不是 500**。资源紧张是「稍后重试可能成功」，SQL 写错是「必须报警」。
  混成 500 会让监控天天误报，也让调用方不知道该不该重试。
- **限制放在功能粒度上**。检索是纯 CPU 的融合打分，它把 CPU 吃满时台账读写也会一起卡。
  在检索入口设一道闸，热点就外溢不到主业务——这比调大整个服务的线程数有效。
- **登录锁定保护的是 CPU，不只是账号**。BCrypt 每次校验要几十毫秒，这是防离线爆破的优点，
  在线爆破时却变成放大器：几十个无凭证请求就能占满一个核。锁定期内跳过密码校验，
  实测响应从 ~90ms 降到 ~18ms（见 `docs/开发记录.md`）。
- **按用户名而不是按 IP 计数**。实验室出口通常是同一个 NAT，按 IP 会把整个实验室一起锁掉。
- **liveness 与 readiness 分开**。数据库挂了应该摘流量（readiness DOWN），而不是重启进程
  （liveness UP）——重启治不好数据库。两者都匿名可达，否则容器编排拿到 401 会一直重启。

**已知边界**（要主动说）：

- 登录计数在内存里，多副本各算各的、重启即清零。定位是「防脚本误刷与低级爆破」，
  不是「抗分布式撞库」；后者需要认证网关或共享存储，不在登录锁这一层的职责里。
- 3 秒 / 5 秒 / 4 个许可是按单库、四核量级估的初值，不是压测结论。
  真实部署要按 `hikaricp_connections_pending`、`tomcat_threads_busy` 这类指标回头调。
- 报表超时路径在本地数据量下不会触发，靠 `ReportTimeoutTest` 构造异常验证翻译逻辑；
  真实超时要等数据量上来后才见得到。

### 4.6 （主线二）盘点：把「可对账」变成真的能对上

入库、出库、报废都只在账内自洽——它们保证的是「我登记的每一笔都算对了」，
但**没有任何机制能发现漏登记的动作**：移液没记、倾洒没报、拿错瓶子放回去，
账会慢慢偏离实物，而界面上一切正常（库存视图读批次表，流水页读流水，各自都对）。
盘点是唯一能把两条线对上的动作，也是这个系统里唯一会**修改历史余额**的地方。

四个决定：

- **账面量在建单时冻结**（`stocktake_item.book_quantity`）。实盘量的语义是「相对于我数的那一刻」，
  基准必须跟着冻结；否则录入过程中别人出库一次，实盘减账面算出来的差异就是错的。
- **确认时用条件更新校验基准**：`UPDATE ... SET available_quantity = :actual WHERE id = :id
  AND available_quantity = :book`。返回 0 就说明盘点期间这批库存被改过，整张单失效，要求重盘。
  直接覆盖成实盘量会把期间发生的业务量悄悄吞掉。
- **整单原子**。任何一条对不上，整张单回滚、状态留在 `SUBMITTED`。不允许「盘了 50 条确认了 47 条」——
  剩下的 3 条会基于一个已经失效的快照。
- **差异写 `ADJUST` 流水，不直接改数字**。台账只增不改：盘亏 -20 mL 与「这条记录被改成 2980」
  是两件事，前者能追溯，后者抹掉历史。流水里的 `balanceAfter` 照常写，所以调账之后对账立刻能重新对平。

**零差异也必须校验基准。** 这一点是实测踩出来的：最初的实现「差异为 0 就跳过」，
结果零差异条目完全不校验——建单时 2980，盘点期间报废 10 变成 2970，实盘照样录 2980（差异 0），
确认静默通过：盘点单宣称盘过 2980，库里有 2970，而**对账仍然显示一致**（2970 = 2970）。
这条恰恰是最常见的路径（多数批次本来就对得上），也就是最常见的失败方式。现在的语义是
「每一条都验证基准没变，只有差异为 0 才不写流水」，由 `StocktakeTest#zeroDiffItemStillVerifiesBookQuantity` 守住。

**对账接口（`/api/chemicals/{id}/reconcile`）** 是「可对账」的落地，不是另一套计算：

| 层次 | 比对 | 能发现什么 |
|---|---|---|
| 逐批次 | 批次表余额 vs 该批次**最后一笔**流水的 `balanceAfter` | 具体是哪一瓶错了 |
| 汇总量 | 批次可用量之和 vs 全部流水 `quantity` 之和 | 总体口径上的偏差 |

两层都看：只看总量，两个批次一多一少互抵就查不出来；只看逐批次，发现不了汇总口径的问题。
总量用 `sumAvailable`（**含已过期**）而不是库存视图里的「可用量」——对账比的是账实是否一致，
与效期无关，过期批次的余额两边都计。没有流水的批次判为不一致（入库必写流水，它是异常状态）。

**已知边界**：全盘一次生成「有库存批次数」条明细，批次上千时要分批建单；同一批次不能同时出现在
两张未完成的盘点单里（建单时挡住，否则后确认的那张必然条件更新失败）；提交人与确认人分开只留痕
不强制——单管理员部署下强制会把流程卡死，要分离得先有「多人具备 ADMIN」这个前提。

### 4.7 （主线一）开封：包装有效期不等于可用期

批次上的 `expire_date` 是供应商按**未开封**状态标注的包装有效期。试剂开封后稳定性重新起算：
甲醇吸湿、过氧化氢缓慢分解、乙醚久置生成过氧化物。只看包装有效期，就会把一瓶开封三个月、
早已变质的甲醇当成「还有半年」继续发出去——而这恰恰是台账最该拦住的事情。

做法是**物化一列 `usable_until`**，而不是在每处现场取较小值：

| 时机 | `usable_until` |
|---|---|
| 入库 | = `expire_date`（未开封，按包装有效期） |
| 开封（档案配了 `postOpenDays`） | = `min(expire_date, 开封日 + postOpenDays)` |
| 开封（档案没配） | 不变，只留 `opened_at/opened_by` |

出库 FEFO 过滤、库存视图的可用总量、效期预警、报表的临期/过期统计，全部只读这一列。
若各处现算，同一套判定会复制到四个查询里，迟早出现「库存视图说可用、出库说不可用」——
而这类不一致在台账系统里比直接报错更难查。

四个决定：

- **只提前，不延后**。取 `min`，开封不可能凭空延长保质期。
- **重复开封直接拒绝**，不覆盖时间。覆盖等于「换一瓶新的」，会把一瓶一直开着的试剂
  重新赋予保质期，恰好抹掉正要跟踪的变质期。
- **不写 `StockLedger`**。开封不改数量；塞一条 `quantity=0` 的流水会稀释「这一笔有变动」的信号，
  流水页也会出现大段与数量无关的行。审计要的「谁在什么时候开的封」记在 `opened_at/opened_by` 上。
- **开封后不变更早期批次或未开封批次的可用期**。开封针对的是这一瓶，不是整个试剂。

**预警文案区分两种原因**：同样一句「这瓶今天不能用」，「开封后可用期已过」多半是这瓶用得太慢，
「包装已过期」是根本没动过——库存视图的 alerts 分别措辞，因为处理决定不同。

**已知边界**：开封不可撤销（没有「点错了」的回退动作，误开只能报废或重开一批）；
`postOpenDays` 事后修改不回溯已开封批次——可用期在开封那一刻就落库了，这是物化列换来的确定性与代价；
开封后可用期没有单独的「进度提醒」，靠的是同一套 `withinDays` 预警，所以刚开封就会进入预警窗口。

### 4.8 （旁路）禁忌混放：未覆盖必须显式

「甲醇的禁忌物有哪些」是穷举问题，top-k 检索一定会漏；而混放判定只有两种确定结果：
**有依据的冲突**，或**没有依据**。所以这里不走向量检索，用一张二元关系表
（`chemical_incompatibility`）做两两比对。D14 记录了这个取舍。

四个决定：

- **关系键是 CAS 对，不是 chemical.id**。禁忌是化学品本身的化学性质，不随某条档案
  增删而改变；同一对只存一条，存入时按 CAS 字典序规范化，`CHECK (cas_a < cas_b)`
  与唯一索引一起杜绝正反序重复。
- **只比对有实物库存的试剂**。混放是物理存放问题：档案建过但已经用完的试剂不占柜子，
  算进去只会制造噪音；已过期但瓶里还有东西的批次仍占柜子，所以按
  `availableQuantity > 0` 筛，而不是按是否过期筛。
- **同柜才算混放**。规则命中后，再取两种试剂所有在库批次的 `location` 求交集：
  交集非空才是 `conflicts`；规则命中但当前分柜存放的放进 `separatedConflicts`，
  只提示「继续保持分离」。每条记录都带 `sharedLocations`，调用方不用自己再查柜位。
  同一种试剂分放在多个柜位时，只要与搭档在任一柜位相交，就算同柜。
- **未覆盖单独成字段**。`uncovered` 只表示「规则表没记录」，不是「安全」。如果把它
  混进冲突列表用空原因表示，前端迟早会显示成绿色。报告额外给出
  `coStoredUncoveredCount`：同柜但没有规则依据的组合最危险，应优先补资料。
  `IncompatibilityTest` 专门用「硫酸 + 过氧化氢」锁住这一点：这一对实际很危险，
  但当前 18 份 MSDS 的第 10 节没有直接禁配表述，所以必须留在未覆盖里等人工补资料。
- **种子只写有出处的规则**。`seed/incompatibilities.json` 共 51 对，依据全部来自
  仓库内 MSDS 第 10 节，`source` 写到具体 CAS 的章节，不编造 GB 15603 条款号；
  `IncompatibilitySeedDataTest` 校验 CAS 拼写、重复对与出处字段。
- **演示数据里刻意留了一处同柜违规**。过氧化氢在 `易制爆专柜 C-1` 多放了一个批次，
  与高锰酸钾同柜，用来让混放检查启动后就有真实结果可看；生产环境应关闭演示种子
  （`hazmat.seed.enabled=false`）或移走该批次。

**已知边界**：当前 51 对只覆盖种子里的 18 种试剂，换一批试剂必须重新导入关系；
还没有按 GB 15603 条款号建立完整关系表。接口返回「无冲突」只说明**已知规则里没有冲突**，
不能当成全库安全结论——这也是 `uncovered` 必须与 `conflicts` 一起展示的原因。

柜位是批次上的自由文本，没有库房/柜位主数据：`危化品柜 A-1` 与 `危化品柜A-1`
会被当成两个柜，录入不规范会漏报；只判断是否同一柜，不区分柜内层板与隔间，
同一房间的不同柜也不视为同柜。要做到库房级合规，下一步应把柜位抽成主数据并校验。

---

## 5. 代码结构

```
src/main/java/com/lazybone/hazmat/
├── entity/          Chemical 试剂档案 / ChemicalBatch 批次 / Requisition 申请
│                    RequisitionStatus 状态机 / RequisitionLog 流转记录 / StockLedger 库存流水
│                    Stocktake 盘点单 / StocktakeItem 盘点明细 / StocktakeStatus 盘点状态机
│                    AppUser 用户 / UserRole 角色 / MsdsChunk MSDS 切块（含 hash 与 vector）
│                    ChemicalIncompatibility 禁忌混放关系（CAS 对 + 原因 + 出处）
├── repository/      9 个；扣减、盘点调账、开封 CAS 与状态 CAS 的关键 SQL 都在 ChemicalBatchRepository
├── service/         StockService 入库出库与对账（核心） / RequisitionService 流程（核心）
│                    StocktakeService 盘点建单与调账（核心） / ChemicalService
│                    ChemicalSafetyService 台账与知识库的连接点（试剂 id → CAS → 检索）
│                    IncompatibilityService 在库试剂两两比对、冲突与未覆盖分开返回
├── msds/            MsdsChunker 按章节切块 / MsdsIndexService 增量落库
│                    MsdsLiteralIndex 内存倒排 / MsdsQueryExpander 同义词
│                    MsdsVectorIndex 内存向量快照 / MsdsSearchService RRF 融合
│                    MsdsVectorService 后台编码、失败上限与四元组校验
│                    SearchConcurrencyLimiter 检索并发闸门
│                    MsdsRetrievalProperties 融合深度与权重
│                    ai/ 云端与本地哈希 embedding 客户端、向量编解码
├── controller/      AuthController 登录 / ChemicalController / RequisitionController
│                    StocktakeController 盘点流程 / MsdsController
│                    IncompatibilityController 全实验室混放检查
├── report/          只读报表（MyBatis-Plus）：ReportMapper + XML，库存汇总与出入库趋势
├── security/        SecurityConfig JWT 过滤链与角色 / JwtService 签发
│                    LoginAttemptGuard 登录失败计数与短时锁定
│                    401、403 的统一 JSON 响应
├── config/          OpenApiConfig 接口文档与 Bearer JWT 声明
├── common/          统一响应 + 全局异常（400 业务错 / 401 未登录 / 403 无权 / 404 不存在
│                    / 503 资源不足 / 500 只记日志）
│                    PageResponse 分页结构 / PageQuery 页大小上限、排序白名单、LIKE 转义
└── seed/            SeedRunner 复用真实入库路径灌种子数据；UserSeeder 建演示账号
                     IncompatibilitySeedRunner 灌禁忌关系（与试剂档案分开，可独立补齐）

src/main/java/com/lazybone/hazmat/bench/
                     BenchDataLoader 压测数据装载（默认关闭，--hazmat.bench.enabled=true 才执行）
tools/bench/         run-read.sh 读路径压测编排；压测器本体 Bench 只依赖 JDK 21 虚拟线程

src/main/resources/db/migration/   V1 表结构、V2 分页复合索引、V3 盘点表、V4 开封字段、V5 批次号唯一索引、V6 禁忌关系表；Hibernate 只做 validate，不再自动建表
src/main/resources/mapper/         报表 XML（ReportMapper.xml），只有只读 SELECT
src/main/resources/msds/           18 份按 GB/T 16483 的 16 节结构整理的演示文本（{casNo}.md）
src/test/java/com/lazybone/hazmat/
├── StockConcurrencyTest    30 线程争同一批次，断言「不超发」且「恰好发完」；并发同批次号入库只落一条
├── RequisitionFlowTest     状态机、乐观锁、先到期先出、台账一致性、申请人不能自批
├── DualReviewTest          双人复核：同一个人点两次被拒、两人各一次才通过、并发两票都算数
├── SecurityIntegrationTest 401/403、登录、角色边界、身份来自 token、探活与文档放行
├── PagingQueryTest         分页结构、页大小上限、排序白名单、LIKE 转义、组合筛选、效期预警
├── ReportTest              MyBatis-Plus 报表：分组口径、过期与临期互斥、趋势汇总、参数校验
├── ResilienceTest          登录失败锁定、探针匿名可达、检索并发闸门
├── ReportTimeoutTest       报表超时/连接池耗尽译成 503，SQL 错误仍走 500
├── StocktakeTest           建单快照、盘盈盘亏入账、零差异也校验基准、整单回滚、对账
├── BatchOpeningTest        开封留痕、可用期取 min、重复/并发开封、过期后不再出库、四处口径统一
├── ScrapTest               报废闭环：过期批次出口、跨试剂拒绝、30 线程不超扣
├── ChemicalSafetyTest      详情接口、安全入口、无 MSDS 的 available=false、401/404/400
├── IncompatibilityTest     同柜/分柜/未覆盖三分类、多柜位求交集、无规则显式未覆盖、401/404
├── entity/ChemicalIncompatibilityTest  规则对归一化、拒绝自对与空值
├── seed/IncompatibilitySeedDataTest    种子 CAS 拼写、重复对、出处完整性
└── msds/                   MsdsChunkerTest 切块 / MsdsSearchTest 检索与增量重建
                            MsdsFusionTest 融合元数据 / MsdsSearchDegradeTest 降级
                            MsdsRetrievalEvalTest 24 题 Hit@5
                            MsdsRetrievalMetricsTest Hit@1 / MRR@10 / Recall@5
                            MsdsRrfWeightSweepTest 融合权重扫描
                            MsdsChunkSizeSweepTest 切块粒度 200/400/800 对照
                            MsdsEmbeddingCompareTest local-hash vs bge-m3（默认跳过，需 Ollama）
                            MsdsWeightProfileTest 权重分档（local-hash 3:1 / 语义模型 1:2）
                            MsdsSemanticWeightTest 语义档权重在检索链路中生效
                            MsdsThresholdTest 阈值分布
                            MsdsAskTest 无模型时抽取式回答、casNo 过滤、401
                            MsdsAnswerServiceTest 空命中的两种语义、意图章节优先、无词面依据拒答
                            MsdsAnswerGenerationTest 引用校验：只用被引块、越界作废、拒答判定
                            MsdsGenerationEvalTest 真实生成模型 E9（默认跳过，需 Ollama + 生成端点）
                            ai/OpenAiChatAnswerClientTest 本地假服务验证请求体、5 条上限、截断、独立端点与错误映射
```

MSDS 检索的切块规则、检索打分与后续阶段见 [docs/msds-rag-设计.md](docs/msds-rag-设计.md)；
架构取舍与「什么条件下会改主意」见 [docs/架构决策.md](docs/架构决策.md)；
实验数字与复现命令见 [docs/实验记录.md](docs/实验记录.md)。

---

## 6. 已知缺口（被问到要主动说）

- 用户体系是最小实现：只有登录、角色与停用，没有注册、改密、找回密码与账号管理接口；
  种子账号密码来自配置，生产必须改掉。角色写进 JWT，改角色或停用账号后，
  已签发的 token 在 8 小时有效期内仍然可用——要即时踢下线需引入 token 黑名单或版本号，
  本项目没有做。台账里的申请人/审批人存用户名而不是外键，用户改名不会影响历史签名，
  但显示名与账号的对应关系要另外维护。
- 双人复核只按 `hazardLevel` 分了「一人 / 两人」两档，没有配「哪些试剂要三级审批」。新增档位要动状态机。
- 报废目前是直接扣减 + 留痕（记录原因和操作人），没有独立的报废单与审批流；若合规要求审批，应在 `scrap` 前加一层单据状态机。
- 盘点：提交人与确认人分开只留痕、不强制（单管理员部署下强制会卡死流程）；全盘一次生成
  「有库存批次数」条明细，批次上千时要分批建单；差异直接调账，没有「确认后仍需复核」的第二道关。
- 开封管理：开封不可撤销（点错了只能报废或另开一批）；`postOpenDays` 事后修改不回溯已开封批次，
  可用期在开封那一刻就已落库；开封是「整瓶一次性开启」的粗粒度模型，没有「分次取用、剩余多少天」
  的连续跟踪，也不支持一瓶同时用于多个实验的用量分摊。
- 对账是**手动调用的查询接口**，没有定时巡检与告警：账不平不会主动通知，得有人去看。
  真实系统应该定时跑全量对账并在 `consistent=false` 时报警。
- 禁忌混放的关系表目前只有 51 对，全部来自仓库内 18 份 MSDS 第 10 节，**没有按 GB 15603
  建完整关系**；换一批试剂需要重新导入。`uncovered` 是「没有依据」而不是「安全」，
  不能把接口返回的空冲突列表当成全库安全结论。混放检查已按批次 `location` 判断同柜，
  但柜位没有主数据、是自由文本，拼写不一致会漏报；也不区分柜内层板与同房间不同柜。
  检查是即刻快照，没有历史轨迹与定时巡检。
- 三类知识源（MSDS / 国标 / SOP）已接通同一套切块与检索链路，但仓库里的国标与 SOP
  是明确标注「演示样例摘录，非标准全文」的改写文本，**不能作为合规依据**；接入真实标准
  需要重新授权语料并复核条款号。
- 向量仍存在 `msds_chunk.vector` 文本列里、检索时全量加载做暴力余弦；`VectorStore`
  抽象与 `memory`/`pgvector` 配置项已就位，pgvector 实现尚未落地（计划 B3）。
- 没有定时任务：效期预警是查询时算的，真实系统应该有定时扫描 + 通知；盘点提醒同样没有。
- 过载保护只在单实例内生效：登录失败计数与检索许可都在进程内存里，多副本各算各的；
  容量参数（连接池 10 / 等 3 秒、语句超时 5 秒、检索 4 个许可）是按小规模估的初值，
  没有压测数据支撑，上线后要照实际指标回头调。
- 分页用 `LIMIT/OFFSET`，深翻页（几十万条以后）会随页码变慢，且并发写入时
  可能出现跨页重复。真实大数据量应改用游标分页（`WHERE (created_at, id) < (?, ?)`）。
- 关键词检索走 `LIKE '%关键词%'`，用不上普通索引；中文也没有分词，搜「甲醇溶液」
  不会自动拆成「甲醇」+「溶液」。要真做全文检索得上数据库全文索引或独立搜索引擎，
  不是在这个项目里堆 SQL 能解决的。
- 表结构从 Flyway 的 `V1__baseline.sql` 开始。用旧版本（`ddl-auto=update`）建过表的 MySQL 库
  没有 `flyway_schema_history`，切换后首次启动会因「表已存在」失败；需要备份后清库重建，
  或手工补一条 baseline 记录。本地 H2 是内存库，每次启动都是新库，不受影响。
- 迁移脚本只使用 H2 与 MySQL 8 都支持的语法，但 H2 毕竟不是 MySQL：字符集、排序规则、
  锁行为仍有差异。涉及这些语义的改动要在真实 MySQL 上再跑一遍，不能只信 H2 测试。
- MSDS 检索已接入向量路与 RRF 融合（24 题题集：字面 17/20、向量 6/20、融合 18/20）。
  **Hit@5 不等于首条精度**：融合的 Hit@1 只有 6/20，低于纯字面的 11/20，MRR 0.512 也低于 0.687——
  3:1 权重是用召回换掉了首条排序，这是 WIP，不是完成态。指标与机制见
  [docs/实验记录.md](docs/实验记录.md) §1.3。
- 默认没有云端 key，向量路是本地哈希，**不具备语义泛化能力**（「酸溅到眼睛里」仍漏召回）。
  E4 已在本机实测 bge-m3（RTX 3060 Laptop 6GB，2026-11-05）：纯向量 Hit@5 20/20、Hit@1 14/20，
  明显优于 local-hash 的 7/20 与 2/20。但**不能把 3:1 直接带过去**——bge-m3 下 3:1 反而把融合
  Hit@5 从 20/20 压到 18/20，最优是 1:2（20/20、MRR 0.867）。代码已按模型自动分档：
  local-hash 走 3:1，语义模型走 1:2，切模型不需要手工改权重；默认配置仍是 local-hash，
  切换后要重跑评测刷新数字。数字与命令见 [docs/实验记录.md](docs/实验记录.md) §1.5，
  理由见 [docs/架构决策.md](docs/架构决策.md) D3/D9。
  `min-score` 目前为 0.0，原因同样是本地哈希的相关分与无关分区间重叠。
- `/ask` 的生成路径已实现并有引用校验，默认 `hazmat.ai.chat-enabled=false`。
  已跑过真实生成模型的端到端评测（E9，2026-11-06）：本机 bge-m3 检索 + 在线 DeepSeek 生成，
  20 道可答题「可答 20/20、生成 18~20/20、引用命中标准节 19~20/20、生成无引用 0」
  （同一配置三轮的波动范围）；4 道陷阱中 2 道明确拒答、2 道给出有原文依据的否定；
  另加 24 道库外问题，24/24 明确拒答。20 条完整回答逐条对照块原文，未发现不忠实陈述。
  **边界仍然要说清**：忠实度是单人抽检、未查遗漏，不是独立评审；库外拒答率只对
  这 24 道题成立。默认的本地 3B（`qwen2.5:3b`）同题集只有可答 13/20、引用命中
  标准节 10/20，还会把无关问题硬答，**不能当默认生成路径**——这也是
  `chat-enabled=false` 至今未改的原因。数字与方法见
  [docs/实验记录.md](docs/实验记录.md) §1.6。
  意图章节映射（误食→§4、泄漏→§6……）按 MSDS 的 16 节结构写死，
  接入国标、SOP 等其它知识源时要按 `doc_type` 另配，不能直接复用章节号。
  这些是阶段 4 的完成边界，设计见 [docs/msds-rag-设计.md](docs/msds-rag-设计.md) §6。
- pgvector 规模实验（E5/E6，`tools/bench/pgvector_bench.py`，1024 维合成向量）：
  1 万块全表扫描 24 ms、HNSW 1.1 ms，但它只召回 0.890；10 万块全表 320 ms、HNSW 3.1 ms，
  召回掉到 0.530，`ef_search` 拉到 400 才 0.815、`m` 加到 32 也只有 0.736。
  **瓶颈是召回不是延迟**；索引约 8 KB/块（10 万块 781 MB，且 m=8/16/32 实测同体积，
  体积由 1024 维向量副本主导）。当前 288 块继续用全表扫描，阶段 2 上量后必须用真实
  bge-m3 向量重测再定参数。数字与命令见 [docs/实验记录.md](docs/实验记录.md) §1.7。
- MSDS 文本由 PubChem 公开安全数据整理，仅用于演示，不构成实际安全操作依据；
  真实系统要接供应商提供的正式 SDS。默认不调用云端模型，开启后正文会发送到第三方服务。
- `/api/msds/reindex` 与 `/api/msds/status` 现在限 ADMIN。它们仍建议放在网关或内网之后：
  `reindex` 会清除编码暂停并触发模型调用，账号泄露时的影响不止是「重建索引」。
