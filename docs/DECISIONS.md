# 架构决策记录 / Decisions

> 每条决策记录"当时为什么这么定"。**已接受的决策不允许在实现中被悄悄推翻**；要推翻必须新增一条并标注 `supersedes`。

---

## D-001 纯 Java 重写，不使用 Go 运行时

**状态**：已接受（2026-11-07）

**背景**：现有实现为 Go，Java 侧只能通过子进程调用，单次固定开销 52ms，且引入跨语言部署负担。

**决策**：内核 100% 用 Java 重写。生产环境**不存在** Go 进程、不存在 JNI、不存在共享库。

**代价**：需要证明行为一致（靠冻结语料），需要重写 6k 行逻辑。

**被否决的方案**：
- JNI/FFM 调用 Go 编译的动态库 —— 需要为每个平台编译 `.so/.dll`，与"丢进若依就能用"直接冲突。
- 保留 Go 作为 sidecar —— 就是现在的痛点，运维成本不变。
- GraalVM 把 Go 嵌进去 —— 不可行。

---

## D-002 金额用 `long` 缩放整数，不用 `BigDecimal`

**状态**：已接受

**背景**：`BigDecimal` 语义正确但慢 10–50 倍，且每次运算产生对象；`double` 会造成确定性的漂移（实测 100 万行 +329 元）。

**决策**：内核金额类型为 `long`（单位 10^-scale）。`BigDecimal` 仅出现在边界（HTTP/DB/CSV）。

**代价**：需要自己做溢出检查与格式化；精度上限被 `long` 限制（scale≤6 时约 ±9.2e12 元）。

**缓解**：`Money.parse` 对超范围输入显式拒绝（与 Go 一致），不是静默截断。

---

## D-003 行数据用列式 + 字典编码，不用 `Map<String,String>`

**状态**：已接受

**背景**：Go 版 `Row{Coord map[string]string}` 在 Go 里可以接受（小对象、无对象头开销），Java 照抄会因对象头 + HashMap 节点导致 1M 行占用 1.5–2GB。

**决策**：

```
RowBatch {
  int[]    dimIds;     // 每个维度成员在字典中的 id
  long[]   amounts;
  int      dimCount;
}
DimensionDictionary {  // 每维度独立
  Map<String,Integer> ids;
  List<String>        members;
}
```

只在**输出**和**解释**时把 id 还原成字符串。

**代价**：调试时不直观；需要一次性转换。

**收益**：内存约降为 1/8–1/10，GC 压力可忽略，且为 P9 流式打基础。

---

## D-004 并行只在池级，输出必须有序

**状态**：已接受

**背景**：实测 Go 版 8 线程相对 1 线程只有 1.6 倍提升（I/O 与串行输出是瓶颈），但并行带来的不确定性成本极高。

**决策**：

- 任务单位 = 一个池。
- 用固定大小 `ExecutorService`（或 JDK21 虚拟线程 + 信号量）执行，结果按池键顺序收集。
- 严禁 `parallelStream()` 写共享集合。
- 每池结果自包含，池间无共享可变状态。

**代价**：池内无法并行（单个超大池仍是单线程）。

**缓解**：超大池（>100 万目标）在 P9 里单独处理。

---

## D-005 结果发布用状态翻转，不用目录 rename

**状态**：已接受

**背景**：Go 版通过"写临时目录 → rename 为正式目录"实现原子发布。在 Windows 上实测 50 次有 1 次 `Access is denied`（杀软/索引器持锁）。

**决策**：

1. 明细以 `run_id` 写入，状态 `RUNNING`；
2. 全部成功且守恒后，`UPDATE allocation_run SET status='PUBLISHED'`（单条小事务）；
3. 未 `PUBLISHED` 的 run 对外一律不可见。

**收益**：原子性由数据库保证，跨节点也成立；不存在半成品目录。

**代价**：需要清理 `FAILED` 的残留明细（定时任务）。

---

## D-006 持久层可插拔，默认 MyBatis，可完全关闭

**状态**：已接受

**背景**：若依用 MyBatis，`ruoyi-vue-pro` 用 MyBatis-Plus；强行绑定一种会让另一半用户无法接入。

**决策**：

- 内核不碰数据库。
- `RunRepository` 是接口，`allocation-persistence-mybatis` 提供默认实现。
- `allocation.storage.enabled=false` 时使用内存实现（适合试算、单测、无库环境）。

**代价**：多一层抽象。

**约束**：抽象只包"存取 run/pool/line"，**不包事务边界**——事务由 starter 控制。

---

## D-007 不移植 Studio / 工作台 / 前端

**状态**：已接受

**背景**：Go 版有 12,268 行工作台代码（规则设计器、任务面板、可视化实验室、对比页）。

**决策**：不移植。宿主工程用若依的代码生成器 + 权限 + 菜单自行生长页面。

**理由**：一旦开始写页面，内核会被业务细节污染；而且每个客户的页面需求都不同。

**代价**：交付时"看起来"没有 Go 版完整。

**缓解**：提供 `POST /allocation/preview` + `explain` 两个接口，足够生成一个"分摊结果解释"页面。

---

## D-008 Go 仅用于一次性冻结语料

**状态**：已接受

**背景**：需要证明行为一致，但不想保留 Go 依赖。

**决策**：P0 阶段用 Go 产出 `golden/`，之后 Go 归档到 `legacy/`，不参与构建、不进 CI、不进生产镜像。

**约束**：

- `golden/` 不可变，变更走 `[SEMANTICS]`。
- 任何人不得在 CI 里下载/编译 Go。

---

## D-009 不使用 Lombok

**状态**：已接受

**理由**：金额类型的 `equals/hashCode/toString` 是语义的一部分，生成代码会隐藏"两个 Money 相等意味着什么"（scale 不同是否相等？）。用 `record` 显式表达。

---

## D-010 starter 不锁定 Spring Boot 版本

**状态**：已接受

**背景**：若依主线已到 Spring Boot 4.x（JDK 17+），同时维护 3.x 与 2.x 分支；`ruoyi-vue-pro` 在 3.2。

**决策**：

- 依赖声明用 `provided` / `optional`，不传递 Spring Boot BOM。
- 只用 `jakarta.*`，不出现 `javax.*`。
- 自动装配用 `@AutoConfiguration` + `AutoConfiguration.imports`。
- 提供 `allocation-bom` 供宿主统一管理版本。

**验证**：CI 分别在 SB 3.2 与 SB 4.x 下跑一次 `example` 模块的冒烟测试。

---

## D-011 错误只抛一种异常

**状态**：已接受

**决策**：所有业务错误抛 `AllocationException(ErrorCode, message, context)`，`context` 至少含 `stageId/allocationId/poolKey`（若适用）。

**理由**：调用方只需要处理一种异常；HTTP 层由 `ErrorCode → status` 映射表统一转换。

---

## D-012 不实现通用表达式引擎

**状态**：已接受

**背景**：有人会想"规则里支持公式"。

**决策**：规则 DSL 只支持声明式字段（share、filter、target mode）。需要计算时，由宿主在**输入阶段**算好写成 Driver。

**理由**：表达式引擎会引入无穷的语义歧义与安全面，且无法被冻结语料覆盖。
