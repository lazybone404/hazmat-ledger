# 高校实验室危化品领用与台账管理

实验室的危化品，原本靠微信群加纸质登记——谁领了多少、有没有批、还剩几瓶，事后都追不到人。而实验室安全检查要求台账能回答**谁在何时领了多少、由谁批准、现在还剩多少**，人工登记给不出唯一依据。

这个系统把它搬进来，重点不是「把纸表变成网页」，而是**让账能被验证**：每一笔出入库都有流水，每条流水记着当时之后的余额，任何时候都能拿批次余额和流水比对，对不上就说明有笔账丢了。

## 两条主线

| 主线 | 链路 | 回答的问题 |
|---|---|---|
| 一 | 领用申请 → 双人审批 → 按效期出库 → 台账 | 一笔领用怎么走完？并发下怎么不超发、不重复批？ |
| 二 | 建单冻结 → 实盘录入 → 确认调账 → 对账 | 账和实物对不上怎么办？怎么证明账是对的？ |

两条线用同一个收口：**对账**。主线一保证「每一笔都算对了」，主线二负责发现「有没有漏记的动作」——没有盘点，账自洽但可能与实物脱节。

权限、分页、报表、过载兜底是支撑能力；开封与可用期并入主线一的出库环节。

## 目录

| 目录 | 内容 |
|---|---|
| [`hazmat-ledger/`](hazmat-ledger/) | 完整实现：试剂建档、分批入库、开封与可用期、按效期出库、审批流转、双人复核、报废处置、库存盘点、台账对账、只读报表、MSDS 安全信息片段检索（独立实验模块） |

运行方式、接口表与设计取舍见 [`hazmat-ledger/README.md`](hazmat-ledger/README.md)。

## 怎么跑

Spring Boot 3 + Maven，目录下有 `mvnw`，不需要预装 Maven，也不需要任何中间件。

```bash
cd hazmat-ledger
./mvnw spring-boot:run          # Windows: mvnw.cmd spring-boot:run
```

默认 H2 内存库，8090 端口，启动时自动灌入演示试剂与批次。切 MySQL、Docker 部署见子项目 README。

## 技术栈

Java 21 · Spring Boot 3.5 · Spring Data JPA · Flyway · MySQL / H2 · Spring Security（JWT）· MyBatis-Plus（只读报表）· JUnit 5 · Docker

## 我在这个项目里最在意的四件事

**一、并发扣减不能超发。** 条件更新（`UPDATE ... WHERE available >= ?`）把「判断够不够」和「扣减」放进同一条 SQL，由数据库原子完成，不靠应用层加锁。30 线程抢 100 库存、每次扣 10，恰好 10 次成功、余量归零、流水逐笔对平。

**二、状态不能走到不该去的地方。** 允许的流转收在枚举的一张表里，终态不可逆；需要先读后写的流转用 `@Version` 乐观锁。高危试剂用中间态 `PENDING_SECOND` 实现双人复核，而不是记一个「已批次数」。

**三、账要能验。** 每次出入库写一条流水，并记录该笔发生后的余额——每个批次最后一笔流水的余额必须等于批次实际余量，对不上就是有笔账丢了。盘点确认时用条件更新校验账面基准，任一条对不上整张单回滚。

**四、效期要正确。** 出库按先到期先出，强制过滤已过期批次；开封后的可用期取 `min(包装有效期, 开封日 + 开封后可用天数)`，出库、库存视图、预警、报表四处只读同一个字段。

## 关于数字

代码里的数字都是实测跑出来的：

- 出库并发：30 线程抢 100 库存、每次扣 10，**恰好 10 次成功**
- 同批次并发报废：32 并发 1385 QPS，13000+ 次扣减全部成功，**余额与流水逐笔对得上**
- 压测发现入库批次号查重是 O(n)，改为唯一索引点查后，同一试剂连续入库**吞吐提升 26 倍**且不再随批次数下降
- 全量测试 125 条，覆盖并发不超发、双人复核、盘点回滚、开封并发

## 关键设计速查

| 关注点 | 代码位置 | 验证方式 |
|---|---|---|
| 出库怎么不超发 | `ChemicalBatchRepository.deduct`（条件更新） | `StockConcurrencyTest`：30 线程抢 100、每次 10 → 恰好 10 次 |
| 审批并发怎么处理 | `RequisitionService.review`（乐观锁 + 新事务重试） | `DualReviewTest`、`RequisitionFlowTest` |
| 双人复核怎么落 | `RequisitionStatus.PENDING_SECOND` + 比对 `firstReviewer` | `DualReviewTest.samePersonCannotApproveTwice` |
| 台账凭什么可信 | `StockLedger.balanceAfter` + `StockService.reconcile` 两层对账 | `RequisitionFlowTest` 余额断言、`StocktakeTest` |
| 盘点为什么不会吞掉期间业务 | `StocktakeService.confirm`（`WHERE available = :book`） | `StocktakeTest#zeroDiffItemStillVerifiesBookQuantity` |
| 开封后为什么不再被选中 | `ChemicalBatchRepository.findAvailableBatches` 读 `usable_until` | `BatchOpeningTest#openedBatchPastUsableUntilIsSkippedOnIssue` |
| 缓存为什么只放档案 | `cache/ChemicalCache` 类注释 + 压测数据 | 边界与降级见「设计取舍 4.1」 |
