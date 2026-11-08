# 完整实施计划 / Master Plan

**目标产物**：`allocation-java` —— 一个可直接放入若依（Spring Boot 3.x/4.x，JDK 21）工程、纯 Java 实现、性能优于 Go 版、行为可被证明一致的分摊组件。

**规模预期**：主代码 12,000–15,000 行，测试 8,000–10,000 行，模块 6 个。

---

## 0. 总览

| 里程碑 | 范围 | 人天 | 单人日历 | 验收标志 |
|---|---|---|---|---|
| **M1 能跑** | P0–P3 | 25–32 | 5–6 周 | 单阶段分摊 L1 全绿 |
| **M2 可交付** | +P4、P7(精简) | 42–52 | 8–10 周 | 可被若依工程调用，多阶段绿 |
| **M3 生产级** | +P5、P6、P8 | 62–74 | 12–15 周 | 审计、对账、幂等、百万行达标 |
| **M4 全量** | +P9 | 74–94 | 15–19 周 | 流式/断点续算 |

> 双人并行可把日历压缩到约 60%；若由 AI 承担编码，编码耗时再压缩，但**验证耗时不可压缩**（语料比对必须真跑）。


> **交付状态（滚动更新）**：P0–P4 与 P8 的百万行目标已完成并通过门禁；P5/P6/P7 的核心逻辑已在
> `io.allocation.kernel.service` 与 `io.allocation.kernel.reconcile` 中实现并经测试，剩余的是适配层
> （JDBC/MyBatis 存储、Spring starter、REST 绑定）。详细差异见每节末尾的「状态」行。

---

## 1. 模块结构

```
allocation-java/
├── pom.xml                          (parent, dependencyManagement 同时兼容 SB 3.2 / 4.x)
├── allocation-kernel/               纯 Java 21，零第三方依赖
│   └── src/main/java/io/allocation/kernel/
│       ├── money/        Money, Scale, Rounding, Overflow
│       ├── apportion/    Apportion, ResidualAllocator, Identity
│       ├── rule/         Spec(record), Stage, Target, Allocation, Filter, Hierarchy
│       ├── plan/         Compiler, Plan, StageGraph, Route
│       ├── exec/         Executor, PoolPlanner, PoolResult, DriverIndex, Row
│       ├── evidence/     Ledger, Lineage, Conservation
│       └── error/        AllocationException, ErrorCode
├── allocation-conformance/          测试专用：golden 运行器 + 属性测试 + 有理数 oracle
├── allocation-spring-boot-starter/  自动装配、配置属性、模板类、虚拟线程作业
├── allocation-rest/                 Controller、DTO、OpenAPI、异常映射
├── allocation-persistence-mybatis/  Mapper、DDL、仓储实现（可被宿主替换）
└── allocation-example/              最小示例（含若依接入片段）
```

**硬约束**：`allocation-kernel` 的 `pom.xml` 里只有 JUnit（test scope）。CI 有检查。

---

## 2. 阶段计划

### P0 语料冻结（一次性使用 Go）

| 项 | 内容 |
|---|---|
| 目标 | 把 Go 参考实现变成"冻结的期望值" |
| 交付 | `golden/` 全量语料 + manifest + 冻结工具 + `tools/corpus-gen` |
| 门禁 | 语料自校验：同一输入跑两次输出 SHA-256 相同；manifest 覆盖 C01–C10 |
| 人天 | 3–4 |
| 备注 | **这是唯一使用 Go 的阶段**。完成后 Go 源码归档到 `legacy/`，不再参与构建 |

细分任务：
1. `corpus-gen`：按 C01–C11 生成输入（固定 seed，可重复）
2. 批量调用 Go 引擎产出 `expected/`
3. `corpus-freeze`：计算并写入指纹
4. `golden/README.md` 说明每个类别的意图

---

### P1 定点内核（`money` + `apportion`）

| 项 | 内容 |
|---|---|
| 目标 | 把 `SEMANTICS.md` §1–§2 变成可证明的 Java 代码 |
| 交付 | `Money`, `Scales`, `Overflow`, `Apportion`, `Identity` |
| 门禁 | P1–P8 属性全绿；C02/C04 语料全绿；`Apportion` 与 Go 微基准误差 0 |
| 人天 | 4–6 |

细分任务：
1. `Money`（record：`long units` + `int scale`），`parse/format/plus/minus/multiplyExact`
2. `Apportion`（`long` 快路径 + `BigInteger` 慢路径，行为完全一致）
3. 有理数 oracle（仅 test 源集）
4. jqwik 属性 P1–P8
5. 边界语料：MinInt64 / MaxInt64 / 0 / 负 / 单目标

**关键验收**：`Apportion(101, [1,1,1], [A,B,C]) == [34,34,33]`，且置换输入不改变结果。

---

### P2 规则与计划（`rule` + `plan`）

| 项 | 内容 |
|---|---|
| 目标 | 规则 DSL 的 Java 模型 + 编译期校验 + DAG 拓扑 |
| 交付 | `Spec` 等 record、严格 JSON 解析（未知字段拒绝）、`Compiler`、`Plan` |
| 门禁 | C05/C08 规则全部可编译；20 条非法规则全部被拒且错误信息含定位 |
| 人天 | 8–12 |

细分任务：
1. record 模型（sealed interface 表达 `Target.Mode`）
2. 严格解析（Jackson `FAIL_ON_UNKNOWN_PROPERTIES`，禁用隐式类型转换）
3. 校验矩阵：dimensions ≤30、stages ≤100、precision 0..6、id 唯一、grouping 合法、target 完整、conservation 合法
4. 拓扑排序 + 环检测 + 阶段依赖推导
5. 层级展开（父子、环检测、空层级拒绝）
6. 规则快照 SHA-256（规范化 JSON 后哈希，键序稳定）

**注意**：JSON 解析必须与 Go 的 `DisallowUnknownFields` 等价——拼错的字段名不能被静默忽略。

---

### P3 执行器·单阶段（`exec`）

| 项 | 内容 |
|---|---|
| 目标 | 跑通"金额 CSV → 池 → 候选 → 分摊 → 结果" |
| 交付 | `Executor`, `PoolPlanner`, `DriverIndex`, `Row`, `CsvReader`, `CsvWriter` |
| 门禁 | C01/C02/C06 的 L1 全绿；100k 行端到端 < 300ms |
| 人天 | 10–14 |

细分任务：
1. CSV 解析器：手写 byte 级扫描（禁止 `String.split`），支持 BOM/引号/CRLF
2. `DriverIndex`：按池键 + 权重字段建立索引，重复行求和（`SEMANTICS` §5.1.1）
3. `PoolPlanner`：grouping AS_IS/TOTAL、池金额 checked 累加
4. 候选构建：target mode 三态、负权重策略、键排序
5. 分摊 + 守恒断言 + 零值抑制
6. 结果写出（BOM、固定小数位、行序按池键升序）
7. 列式行表示（字典编码，为 P8 留路）

**性能门槛写死在测试里**：1M 行 < 2s（内存模式，不含 DB）。

---

### P4 多阶段 / 多输入 / MERGE

| 项 | 内容 |
|---|---|
| 目标 | 完整 DAG 执行 |
| 交付 | 阶段路由、share 拆分、MERGE/PASS、来源未命中透传 |
| 门禁 | C05 三阶段语料 L1 全绿；C07 缺数据错误信息含 stage/rule/pool |
| 人天 | 8–12 |

细分任务：
1. 多输入按 share 拆分（`SEMANTICS` §4.2）
2. MERGE 语义：坐标聚合、id 取首个、lineage 边
3. 来源未命中透传（金额不变断言）
4. 阶段间有序收集，禁止跨阶段共享可变状态
5. 错误上下文装配（stage/rule/pool/坐标）

---

### P5 证据与持久化（`evidence` + starter 持久层）

> **状态**：证据文件（五件套）与 `RunRepository` 端口、内存实现、`ddl/` 三方言（已完成并被 `SchemaTest` 执行验证）；JDBC/MyBatis 适配器待做。

| 项 | 内容 |
|---|---|
| 目标 | 用数据库承载审计，替代 189MB CSV |
| 交付 | DDL 4 表、Mapper、run 状态机、幂等键、可选 CSV 归档 |
| 门禁 | 同一幂等键并发提交 10 次，只产生 1 个 run；未 PUBLISHED 的 run 对外不可见 |
| 人天 | 8–12 |

细分任务：
1. 表设计（见 `API.md` §4）
2. 状态机：`PENDING → RUNNING → (PUBLISHED | FAILED | CANCELLED)`
3. 幂等键唯一索引 + 冲突返回既有 run
4. 明细批量写入（JDBC batch，10k/批），事务边界 = 单批
5. 发布 = 单条 UPDATE 翻转状态
6. 证据哈希（run 级 + 文件级）
7. 归档策略（超过 N 行的 run 落 Parquet/CSV.gz）

---

### P6 对账（`reconcile`）

> **状态**：坐标级核对、容差、重复策略、科目汇总、`reconcile` 命令与 `POST` 服务方法已完成；分片流式实现（对应 Go `acceptance.Compare`，用于千万行）待做。

| 项 | 内容 |
|---|---|
| 目标 | 与独立参考结果逐坐标比对 |
| 交付 | `Reconciler`、差异报告、tolerance、分区聚合 |
| 门禁 | 同总额不同分配必须 FAIL；100 万坐标比对 < 3s |
| 人天 | 5–7 |

细分任务：
1. 按全维度坐标聚合（重复行策略可配）
2. 差异四分类：一致 / 金额不同 / 缺失 / 多余
3. 分区哈希聚合控内存（32 分区，同 Go 策略）
4. 报告：`summary.json` + `differences.csv`
5. 退出码语义（0/3/1/2）

---

### P7 Spring 集成（starter + rest）

> **状态**：`AllocationTemplate` 及其默认实现已完成（preview/submit/status/await/explain/reconcile、幂等键、虚拟线程、有界准入）；`starter` 自动装配与 REST 控制器待做，契约见 `docs/API.md`。

| 项 | 内容 |
|---|---|
| 目标 | "加依赖 + 建表 + 写一个 Bean" 即可用 |
| 交付 | `@EnableAllocation`、`AllocationTemplate`、5 个 REST 接口、OpenAPI、异常映射、虚拟线程执行器 |
| 门禁 | 在真实若依工程 30 分钟内跑通一次分摊（有脚本化验收） |
| 人天 | 10–14 |

细分任务：
1. `AllocationProperties`（前缀 `allocation.*`，全部有默认值）
2. 自动装配（`@AutoConfiguration` + `@ConditionalOnMissingBean`，宿主可全覆盖）
3. `AllocationTemplate`：`preview / submit / status / await / reconcile / explain`
4. 作业执行器：虚拟线程 + 有界信号量 + 取消令牌
5. REST：`POST /allocation/preview|runs|reconcile`，`GET /allocation/runs/{id}`
6. 错误映射：`ErrorCode → HTTP status + 稳定业务码`
7. 权限钩子：`AllocationAuthorizer` SPI（默认放行，宿主接若依 `@PreAuthorize`）
8. 数据权限钩子：`TenantProvider` / `DataScopeProvider` SPI
9. Micrometer 指标：池数、行数、耗时、残余分布、失败原因

**验收脚本**：`examples/ruoyi-smoke.sh` —— 空库 → 建表 → 提交 → 轮询 → 对账，全绿。

---

### P8 性能与规模

| 项 | 内容 |
|---|---|
| 目标 | 1M 行 < 2s；10M 行可跑 |
| 交付 | 字典编码、对象复用、JMH 基准、GC 调优记录 |
| 门禁 | JMH 结果进基线；相对基线退化 >10% 则 CI 告警 |
| 人天 | 5–8 |

细分任务：
1. 行表示：`int[] dimIds` + `long[] amounts`（禁止 `Map<String,String>` 每行）
2. 维度字典：`Interner`，成员 → int
3. 池内临时结构复用（ThreadLocal 池化）
4. 结果写出：缓冲 + 手写数字格式化（避免 `String.format`）
5. JMH：`Apportion` 10/100/1000/10000 四档，端到端 100k/1M
6. 与 Go 基线对比报告（写入 `docs/java-port/PERF.md`）

**已知陷阱**：Java 若照抄 Go 的 `map[string]string`，1M 行会吃掉 1.5–2GB 并 GC 抖动。列式改造不是优化，是可行性前提。

---

### P9 流式与断点续算（可延后）

| 项 | 内容 |
|---|---|
| 目标 | 内存不足以放下全部坐标时仍可完成 |
| 交付 | 外部排序、spill 到临时文件、检查点、断点恢复 |
| 门禁 | 1000 万行在 `-Xmx1g` 下完成；中断后可从检查点恢复且结果一致 |
| 人天 | 12–20 |
| 备注 | 不与 Go 的分区文件格式兼容——Go 那套是围绕其运行时设计的，Java 需重新设计。**不要照抄** |

---

## 3. 依赖关系

```
P0 ──┐
     ├── P1 ── P2 ── P3 ── P4 ── P5 ── P6
     │                   │
     └───────────────────┴── P7 ── P8 ── P9
```

- P1 可与 P0 并行（P1 只需少量手写用例即可开发，P0 完成后接入全量语料）。
- P6 可与 P5 并行。
- P9 与其它阶段完全解耦。

---

## 4. 风险登记册

| # | 风险 | 概率 | 影响 | 对策 |
|---|---|---|---|---|
| R1 | `long` 乘法静默溢出 | 高 | 致命 | `Constitution` 第三条 + ArchUnit 禁止裸乘 + 属性测试 |
| R2 | 平局裁决依赖行序 | 高 | 高 | 身份排序 + 置换不变属性 P4 |
| R3 | 对象膨胀导致 OOM/GC 抖动 | 高 | 高 | P3 起就用列式表示，不给自己留"以后优化"的借口 |
| R4 | 并行导致结果漂移 | 中 | 高 | 池级并行 + 有序收集 + 禁止 parallelStream 输出 |
| R5 | 若依分支差异（SB2/SB3/SB4、jakarta、MyBatis vs MP） | 中 | 中 | starter 不锁版本；持久层走 SPI；只依赖 `jakarta.*` |
| R6 | DB 写入成为新瓶颈（比 CSV 更慢） | 中 | 中 | 批量 + 归档 + 可关闭明细表 |
| R7 | 语料覆盖不足，上线才发现边界 | 中 | 高 | C01–C11 强制清单 + 影子运行 3–6 个月 |
| R8 | 范围蔓延到前端/工作台 | 高 | 中 | `Constitution` 第十三条 |
| R9 | 有人为了性能改金额语义 | 中 | 致命 | `Constitution` 第十二条 + 字节比对门禁 |

---

## 5. 工程约定

### 5.1 分支与提交

```
main                     永远可发布
feat/p3-executor         功能分支
fix/...  chore/...  perf/...
```

提交信息：`<type>(<module>): <subject> [skip-ci?]`
行为变更必须带 `[SEMANTICS]` 前缀并附语料变更。

### 5.2 代码风格

- Java 21：`record`、`sealed`、`switch` 模式匹配、文本块、`var`（局部）。
- 不用 Lombok（会隐藏 `equals/hashCode` 语义，金额类型必须显式）。
- 不用 `Optional` 表示金额缺失；用 0 或显式 `NoAmount`。
- 异常只抛 `AllocationException`（带 `ErrorCode` + 上下文）。

### 5.3 每个 PR 的完成定义（DoD）

1. `mvn -pl allocation-kernel,allocation-conformance verify` 全绿
2. `SEMANTICS.md` 已同步（若涉及行为）
3. 新增语料已冻结
4. 宪法 12 条自检清单全部为"是"
5. CHANGELOG 已更新

---

## 6. 进度可见性

每个阶段结束时产出一份 `docs/java-port/reports/<phase>.md`，包含：

- 实际人天 vs 计划
- 语料通过数 / 总数
- 性能数字（JMH）
- 未解决项与风险变化

**没有报告 = 阶段未完成。**

---

## 7. 第一个里程碑的具体验收

M1（P0–P3）完成时，下面这条命令必须全绿：

```bash
mvn -q -pl allocation-conformance test -Dgolden.filter="c01*,c02*,c04*,c06*"
```

并且同时满足：

- `Apportion` 的 8 条属性测试通过
- 100k 行端到端 < 300ms（本机 i5-12490F 基线）
- `allocation-kernel` 依赖树中无第三方框架
- 至少 3 个 C02 用例是**与 Go 引擎逐字节一致**

到这一步，风险最大的部分（算法等价性）就已经被钉死了，剩下都是工程量。
