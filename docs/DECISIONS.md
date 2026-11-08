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

---

## D-013 幂等键的请求摘要包含数据内容，而不是路径

**状态**：已接受（修正 D-006 的实现缺陷）

**背景**：早期实现里，CSV 源的 `request_sha256` 取的是 `baseDirectory` 的绝对路径。于是同目录下
重新导出一份数字不同的 `amounts.csv`，再带同一个幂等键提交，会得到**上一次运行的旧答案**，且没有任何
报错。这是幂等语义被实现成"同一个目录"而不是"同一次请求"。

**决策**：摘要 = 规则字节 + 每个数据集的内容 SHA-256（金额盘按文件内容；内存注入的金额行按其坐标与
金额逐行摘要；驱动盘始终按文件内容）。数据集按名字排序后拼接，行内坐标键排序后拼接——`AmountRow`
的 `Map.copyOf` 迭代顺序不保证。

**已知代价**：每次提交要把每个数据集完整读一遍来算摘要（与执行时的读取是两次 IO）。相比"拿到旧答案"，
这个代价可以接受；真要省掉，应在 M4 的流式管线里把摘要与解析合并成一次读。

---

## D-014 结果坐标存一个 JSON 键，不按维度拆列

**状态**：已接受

**背景**：`allocation_result` 需要能按坐标查回结果，而规则声明的维度数是 1..30。

**决策**：`coordinate TEXT` 存引擎的规范键（JSON 字符串数组），读回时用 `Coord.parse` 还原。

**理由**：拆列意味着 DDL 要跟随业务维度变化，那是把业务模型写进基础设施；而规范键本来就是这个引擎
分组、排序、比对的单位，存它等于存"引擎认得的那个坐标"。代价是按单个维度过滤要走表达式索引，这在
MySQL 8 / PostgreSQL 上可接受。

---

## D-015 终态由 SQL 的 WHERE 子句保证，不由调用方的自觉保证

**状态**：已接受

**决策**：`JdbcRunRepository.update` 的 `WHERE` 带 `status IN ('PENDING','RUNNING')`，更新到 0 行即抛
`ALLOC_005`。

**理由**：`PUBLISHED` 之后不允许再改金额、不允许再写证据，这条规则如果只写在文档里，迟早会被一次
重试或一个手工修数脚本破坏。让数据库拒绝，比让代码评审拒绝可靠。

---

## D-016 阶段缓冲区溢写到磁盘，而不是外部排序

**状态**：已接受

**背景**：执行器原来把每个阶段的行读成 `List<Row>`。1000 万行金额在 128MB 堆下必 OOM，
而这条限制与算术无关——分摊本身只需要"每个池的合计"和"该池的驱动权重"。

**决策**：`RowStore` 是有预算的追加序列。默认在内存里留 250,000 行，超出后整段（含已在内存中的
那些）按同一编码写入临时文件；`forEach` 可以重放任意多次，插入顺序跨溢出边界不变。
预算由 `Executor.Options.maxRowsInMemory` 给出，CLI 是 `--max-rows`。

**理由**：

1. **顺序必须不变**。语料是逐字节比对 Go 的输出，任何"排序后再合并"的方案都要重新证明
   tie-break、行标识序号、证据行序全部不变。保持插入顺序，等于让语料继续当证明。
2. **可重放是硬需求**。合并阶段要两趟遍历（先发血缘、再发成员），外部排序给不出这个，
   除非再写一遍临时文件。
3. **常见运行不该付出代价**。预算内的运行连目录都不创建；溢写只发生在真正大的运行里，
   那时磁盘 I/O 是唯一可行的代价。

**已知代价**：溢写路径按行编码/解码，比内存路径慢；临时文件峰值约为"所有存活阶段的行数 × 约 40 字节"，
10M 行深表实测写出 1GB 证据、临时文件数百 MB（`docs/PERF.md` 第 2 节）。

**没有解决的部分**：驱动表仍整体常驻——分摊要随机访问权重，不是流；按坐标合并的那张 map 也仍在内存里。
这两处若要继续降，需要列式驱动表示与外部排序，各自独立成项，不在本条内。

---

## D-017 服务层对账也走分片流式实现，报告目录按"比了什么"命名

**状态**：已接受

**背景**：服务层的 `reconcile` 原来把整个结果集读成 `List`（`results(runId, 0, Integer.MAX_VALUE)`）、
把参考文件整读进内存、再建两张 map 比较。同一个引擎能在 64MB 里分摊一千万行，但服务层对账一百万行就
会 OOM——这不是性能问题，是公开 API 里的一个坑。

**决策**：

1. 服务层改用 `ShardedReconciler`：结果侧由 `RepositoryAmountRows` 按 5000 行分页从库里取，
   参考侧由 `CsvAmountRows` 流式读文件，比较在磁盘分片上做。`ReconcileOutcome` 因此改为携带
   `ShardedReconciler.Summary`，而不是内存版的 `Reconciler.Report`。
2. 差异报告写到 `reconcile/<runId>/<参考文件 SHA-256 前 16 位>/`。同一 (run, 参考) 重复对账得到同一份
   报告（已存在则不再发布，直接返回该路径）；对不同参考对账则各留一份，互不覆盖。

**理由**：报告是 (run, 参考) 的纯函数——run 发布后不可变，参考文件由摘要钉住。用摘要命名，就不会出现
"第二次对账把第一份证据悄悄替换掉"，也不需要"加时间戳目录"这种越攒越多的办法。反过来，如果直接用
`reconcile/<runId>/differences.csv`，同一 run 先后对两份参考对账时，前一份证据会被覆盖，而调用方
根本不会察觉。

**已知代价**：参考文件在比较前会被读一遍算摘要（与比较时的读取是两次 IO）。相比"证据被覆盖"，这个代价
可以接受；真要省掉，应当让 `AmountRows` 在流式读完后回填摘要、再把临时目录改名——那是拿并发安全换一次 IO，
不值得。

---

## D-018 驱动表按列存，不按行存对象

**状态**：已接受

**背景**：驱动表是唯一**不能**流式处理的数据——某个池要分摊，就得随机访问它所有可选目标的权重。
所以它必须常驻，而它的表示形式就是一次宽表分摊的堆下限：1000 池 × 1000 目标的一百万行驱动，
按 `DriverRow` + `Coord` + `String[]` 存放约 90 字节/行，把整个运行的堆下限顶在 256MB。

**决策**：`DriverTable` 按列存放：每行只留 `int[维度数]` 的成员字典 id 和一个 `long` 权重，成员字符串
按维度去重（同一个 `P1` 出现一百万次也只存一份）。驱动仍然从 `AllocationInput.forEachDriver` 流式读入，
读一行存一行，不再有中间的 `List<DriverRow>`。

**理由**：

1. **它才是宽表的瓶颈**。改造后同一份 1000×1000 负载的堆下限从 256MB 降到 **64MB**，耗时还略降。
2. **不改变语义**。每个访问器回答的仍然是"那一行会回答什么"：`has` 区分"列不存在"和"成员为空"
   （这是 target mode SOURCE 的判定依据），权重原样保留，重复坐标的检测改比坐标对象而不是坐标键。
   语料逐字节仍然一致，是这条判断的证据而不是注释。
3. **流式读入顺带解决了两个拷贝**。原来 `readDrivers` 返回列表、`build` 再建桶，两边各持一份引用。

**已知代价**：

- 成员字典按维度各建一个 `HashMap`，写侧每行每维一次哈希查找（一百万行 × 5 维）。相比省下的
  60MB 常驻内存，这笔 CPU 花得值——实测耗时反而下降，因为 GC 少了。
- 重复驱动行的告警仍然要为每行构造一次 `Coord` 来判重（瞬时，不保留）。要彻底免掉得给表加一个
  "按坐标排序的索引"，那是一次性 O(n log n) 排序，等告警本身变成瓶颈再说。
- 千万级目标坐标没有测过：理论上限仍是"整张表常驻"，只是每行便宜了三倍。

---

## D-019 取消是协作式的，且"CANCELLED"不是"FAILED"的一种

**状态**：已接受

**背景**：`docs/API.md` §2.7 从第一版就写着"协作式取消：设置取消令牌，池边界检查"。真正实现时要回答三个
不好改的问题：在哪检查、状态怎么记、记不了的时候怎么回答。

**决策**：

1. **在池批次边界检查，不池内检查**。`Executor.forEachPool` 每批并发池开始前查一次令牌（顺序分支则每个池
   前查一次）。池是"不可半分配"的单位——一个池要么整池产出，要么不产出。若在池内检查，就要么发布半个池
   （守恒不成立），要么丢弃同一批里已经算完的池（调用方看到的进度与代数对不上）。
2. **`CANCELLED` 是独立终态，不写 `failureCode`**。它是"有人改主意"，不是"算错了"。运维看板上两者必须分开，
   否则每次取消都变成一次事故。因此 `RunRecord.cancelled(...)` 清空失败字段，与 `failed(...)` 并列。
3. **不提交证据**。取消路径不调用 `EvidenceWriter.commit()`，`close()` 丢弃已写内容（D-015 的同一个机制）。
   于是"取消的 run 查不到结果"不是靠状态判断挡住的，而是**根本没有结果**——状态只是解释原因。
4. **不属于本进程的 run 返回 `false`**。取消令牌是进程内的 `AtomicBoolean`，与 run 同生共死。上一个进程留下
   的 `RUNNING` 记录没有任何人持有令牌，声称成功是最坏的回答：调用方会去轮询一个永远不会变的 run。
   正确做法是返回 `false`，由 P9 的中断恢复去处理那条记录。

**已知代价**：

- 停止的及时性由池的粒度决定：一个池要分摊一秒，取消最多晚一秒生效。要更快就得让 `PoolAllocator` 内部
  可中断，那会在最热的循环里引入一次 volatile 读——不支持。
- 取消令牌不持久化，因此"重启后取消"不支持（见上第 4 点）。
- `ALLOC_011` 是新增错误码（Art.15：同一次提交内更新 `docs/API.md` §3）。

---

## D-020 Web 层由自动配置注册成 Bean，不依赖组件扫描

**状态**：已接受

**背景**：starter 的包 `io.allocation.spring` 不在宿主的扫描路径上（若依扫描 `com.ruoyi`）。写在
`@Component`/`@RestController` 上的类在作者自己的测试里会被扫描到，在宿主的工程里不会——这是最容易在交付
半年后才发现的缺陷。

**决策**：`AllocationWebConfiguration` 是一个 `@AutoConfiguration`，通过
`META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 注册，用
`@Bean` + `@ConditionalOnMissingBean` 声明 `AllocationController` 与 `AllocationExceptionHandler`，
并用 `@ConditionalOnWebApplication(SERVLET)` 限制在 Web 应用内。

**理由**：

1. **能被替换**。宿主想加审计端点，继承控制器声明自己的 Bean 即可，不需要与 starter 抢所有权。
2. **批处理进程不会装配一个永远不会被访问的 `@RequestMapping`**，也不需要在批处理里排除 Web 依赖。
3. **advice 作为 Bean 就能生效**：Spring 的异常解析器从上下文收集 `@ControllerAdvice` Bean，扫描与声明等价。
   `AllocationAutoConfigurationTest` 用 `WebApplicationContextRunner` 把这条验证钉住。

**顺带定下的一条**：`@PathVariable` / `@RequestParam` 一律显式命名，同时在父 pom 打开 `-parameters`。
两者都做，是因为前者让契约不依赖编译参数，后者让 Spring 的默认行为生效——只做一半的工程，会在别人用别的
编译参数重建时坏掉。

**已知代价**：多一个配置类，`AutoConfiguration.imports` 里多一行。可接受。

---

## D-021 进度是进程内的实时数字，不进数据库

**状态**：已接受

**背景**：`docs/API.md` §2.3 承诺 `pools / totalPools / rows`。在它实现之前，一个跑十分钟的 run 从提交到结束
每次轮询都返回 0/0/0——从调用方看，这与"请求挂了"没有区别。真正的问题不是"怎么算进度"，而是"进度存在哪"。

**决策**：进度放在执行进程内的 `Map<String, AtomicReference<RunView.Progress>>`，由 `Executor.Progress`
回调从协调线程写入（池批次边界，与取消检查同一处）；`status()` 对**未到终态**的 run 读实时值，对**已到终态**
的 run 读已提交的证据（池级对账行数与结果行数）。

**理由**：

1. **它不是一个需要审计的事实**。"此刻已完成 620 个池"是瞬时状态，5 秒后就不成立。把它写进库意味着
   每个池批次一次 UPDATE，把最热的路径挂在数据库上，换回一个已经很旧的数字。
2. **终态的数字必须是证据，不能是内存**。run 发布后 `pools`/`rows` 从库里读，重启、换进程、事后审计都一致。
   这条界线是"实时值"与"证据"的分界，也是本决策的核心：两套来源服务于两个不同的时间尺度。
3. **零成本接入**。报告点就在 `forEachPool` 消费结果的下一行，与取消检查共用同一个边界；没有额外线程、
   没有定时器、没有采样。
4. **不撒谎**。所有上报的数字都是执行器本来就要维护的计数器（`poolChecks`、`stageOutRows`），不是另立一套
   可能漂移的统计。

**已知代价**：

- 进程崩溃时"最后一个已知进度"丢失。可接受：崩溃的 run 本来就需要 P9 的中断恢复，一个陈旧的进度数字对恢复
  没有帮助。
- 多实例部署时，只有持有该 run 的实例知道实时进度，另一个实例轮询只会看到库里的 0。正确做法是负载均衡把
  轮询粘到同一实例（或由持有者转发），这属于宿主职责，不由内核假装解决。
- 进度粒度是"批"（`workers × 2` 个池），不是"池"。单个池耗时极长时进度会停住——这是诚实的：它确实还没做完
  那一个池。

---

## D-022 中断遗留的 run 由显式截止时间清理，不由启动时猜

**状态**：已接受

**背景**：run 先落库、后执行、终态只写一次（D-015）。这条顺序的好处是崩溃留下"看得见的未完成"，代价是
记录会永远停在 `RUNNING`——而 `cancel` 也正确地对它返回 `false`（D-019 第 4 点），于是调用方没有任何手段知道
"没人在算了"。这是全工程唯一一处"数据库在说谎"的地方。

**决策**：

1. **新增 `RunRepository.byStatus(status, limit)`**（两种实现 + 有 `idx_run_status` 覆盖，无 schema 变更）。
   判定依据必须是数据库，因为知道真相的进程已经没了。
2. **`RunRecovery.abandonInterrupted(repository, startedBeforeEpochMs, limit)`** 把 `RUNNING` 与 `PENDING`
   两种非终态、且 `startedAt < cutoff` 的记录写成 `FAILED` + `ALLOC_012`。`PENDING` 也扫：派发是接受提交那个
   进程里的虚拟线程，进程没了就没有任何东西会捡起排队的 run。
3. **截止时间是参数，不是本进程启动时间**。"早于本进程启动"只在单实例下等于"没人管了"；多实例下另一个实例
   可能在本次启动前一分钟才接下那个 run，它正干得好好的。判断属于调用方，**工具不替它猜**。
4. **计数看结果，不看意图**。`update` 的守卫只改 `PENDING`/`RUNNING`（D-015），所以"查询后、更新前刚好跑完"
   的 run 不会被改写；写回后重新 `find` 一次，只有确实带着 `ALLOC_012` 的才算数。否则会把他人的成功报成
   被放弃。
5. **starter 里做成可选开关**（`allocation.recover-interrupted-runs`，默认 `false`），用
   `SmartInitializingSingleton` 在上下文启动期执行，而不是 `ApplicationRunner`——后者的执行窗口在 Web 服务器
   已经开始接客之后，而那正是这段代码想关掉的窗口。

**已知代价**：

- 一台机器上一个 run 死了，只有人（或这个开关）清理它才有人管；没有自动心跳检测。真正需要的是"run 记一个
  所有者 + 心跳"，那是多实例部署的完整方案，属于 P9 的剩余部分。
- 被清理的 run 已经产出的证据不会被删除：`saveXxx` 从未提交过（D-015），所以库里本来就没有它的结果行；
  但如果有实现把证据直接写进了库，需要自己处理——接口的 `EvidenceWriter` 语义已经写明"不 commit 即丢弃"。

---

## D-023 事件是一个接口，鉴权是一层装饰

**状态**：已接受

**背景**：`docs/API.md` §6 的扩展点表里有两项长期写着"⬜ 计划中"：`AllocationListener` 与
`AllocationAuthorizer`。它们是最容易糊弄的两项——写一个没人实现的接口，表格就绿了。真正要回答的是两个结构性
问题：事件用什么形状，鉴权放在哪一层。

**决策一：`AllocationListener` 只有一个方法 `on(Event)`，事件是 sealed 层次。**

- 五个 `onPublished` / `onFailed` 之类的方法，是把将来一定会增加的事件类型提前钉死在宿主已经编译过的名字上。
  一个 sealed 层次加一个方法，扩展方式是**加一个变体**；宿主 switch 里留 `default` 即可（这条写进了 javadoc，
  而不是留给别人以后踩）。
- 监听器**在运行自己的线程上**被回调，异常被捕获并记日志。正常情况吞异常是错的，这里的替代方案是"一个指标上报
  组件把分摊打挂"，明显更坏。
- **池级不是事件**。10 万池会产生 10 万条事件，而池级进度已经由 `Executor.Progress` 以计数器提供——热路径上
  读一个数，不分配对象。把"进度"和"事件"分开，是这个接口能保持五个变体的原因。
- 取消**单独一个变体**，不并进 `Failed`，理由与 D-019 相同：有人改主意不是故障，看板必须分得开。

**决策二：`AllocationAuthorizer` 是 `allocation-spring` 里的装饰器，不是内核参数。**

- 内核没有"调用者"这个概念。要加就得给 `AllocationTemplate` 的每个方法加一个 `Caller` 参数，而这个参数由宿主
  填、内核无法验证——那是一个看起来像安全、实际上只是礼貌的字段。接口自己的 javadoc 早就写了正确的做法：
  "宿主可以替换成装饰器——鉴权、指标、试运行——而不用继承实现"。这条决策就是那句话被实现一次。
- **先检查，后做事**。装饰器在调用引擎之前检查，所以拒绝的请求不建 run、不读规则、不开连接。副作用是
  "无权读某个 run"与"这个 run 不存在"返回同一个 403——否则无权调用方可以用状态码枚举 run id。这条有测试
  （`AuthorizationTest.aRefusedReadDoesNotRevealWhetherTheRunExists`）。
- **只在宿主声明了 Bean 时才包**。`permitAll` 的装饰器是一次纯开销的栈帧，所以 `ObjectProvider.getIfAvailable()`
  返回空时直接给原对象。
- **进程内调用不受约束**，并且写在了接口文档里。宿主自己的代码已经是可信代码；它若需要同一套规则，就该自己
  调用 `check`——那一行代码知道调用者是谁，而装饰器不知道。

**顺带修掉的一个坑**：`@Bean(destroyMethod = "close")` 在返回类型是接口时会启动失败（"Invalid destruction
signature"），因为 Spring 按**声明类型**校验销毁方法，而 `AllocationTemplate` 没有 `close`。改成依赖 Spring 的
推断（按**实际实例类型**找 `close`/`shutdown`），并让装饰器实现 `AutoCloseable` 转发给被装饰者——否则包一层之后
引擎的线程池永远不会被关，测试里看不出来，生产里表现为"停不下来"。

**已知代价（当时）**：`TenantProvider` / `DataScopeProvider` 仍未实现。多租户不是加一个 SPI 就完事：`allocation_run` 要有
`tenant_id`，唯一键要变成 `(tenant_id, idempotency_key)`，所有查询要带租户条件——那是一次 schema 变更加一次
仓储重写，值得单独一次提交，而不该混在事件与鉴权里。**后续**：`TenantProvider` 已实现（D-031）；`DataScopeProvider`
明确不做——数据权限约束的是"能看到哪些成本中心"，也就是**输入数据**，属于宿主的输入层职责，不是分摊组件的
扩展点（见 D-031 与 API.md §5.2）。

---

## D-024 运行记录的合计是 DAG 的输入，不是各阶段之和

**状态**：已接受

**背景**：这是本工程里少数几个靠"看数字"而不是靠测试发现的缺陷。示例工程第一次跑起来时打印：

```
status=PUBLISHED  input=820000.00  output=820000.00  balanced=true
```

而同一份规则、同一份数据，命令行工具打印 `input=41000000`（精度 2，即 410,000.00）。差正好两倍。

**原因**：`RepositorySink` 把每个池的对账行（`ReconciliationLine`）的 `inputAmount` 累加，当成整个 run 的输入。
每个池的"输入"是进入**该阶段**的金额。两阶段规则里每笔钱都进两次（stage1 一次、stage2 一次），于是合计正好翻倍；
三阶段就是三倍。它甚至"守恒"——input == output——所以没有任何校验会报警，只会把"这笔运行分摊了 820,000"
告诉所有看板。

**决策**：**合计只从 `RunSummary` 取**（`summary.inputAmount()` / `summary.outputAmount()`），也就是执行器在播种
源数据时累加一次的那个数，与 `CONSERVATION_FAILED` 检查用的是同一个值。

1. **删掉 `RepositorySink.inputAmount()` / `outputAmount()`**，而不是改对它们。一个"存在但正确用法是不用"的方法，
   下一个人一定会用错。写入器现在的职责只有：转发证据、统计池数、统计行数。
2. **池级证据不动**。每个 `PoolRecord` 仍然记着自己那一段的输入输出，那是对的——它是审计的骨架，看的是"这个池
   平衡不平衡"，与"这次运行一共动了多少钱"是两个问题。
3. **回归测试按阶段断言**（`MultiStageTotalsTest`）：run 记录 = 10,000（100.00 一次），而每个阶段的池合计各自
   等于 10,000，stage2 的两个池分别是 2,500 / 7,500。任何"把池加起来当总数"的回归都会在第一个断言上失败。

**顺带定下的一条**：`RunView.Progress.pools` 对已完成的 run 是"所有阶段的池总数"（示例里 4 + 12 = 16），
对执行中的 run 是"当前阶段已完成的池"。两者语义不同但都叫 `pools`——当时记为已知的粗糙之处，并打算"等有第二个
消费者再改"。

**后续**：这个理由站不住，已经由 D-030 关闭。粗糙之处不是"多一个消费者才会痛"，而是第一个消费者就会撞上：
两阶段规则从 `4/4` 跳到 `0/12`，任何按 `pools/totalPools` 画的进度条都会从满回到零。修法就是这里写的那个——
给 `Progress` 加 `stageId`——只是当时把"接口变更"当成了推迟的理由，而这次变更的成本（一个字段，1.0 之前）比
一个会倒退的进度条低得多。

---

## D-025 示例工程不是 demo，是一条可执行的断言

**状态**：已接受

**背景**：`docs/API.md` 有三条承诺——"宿主不需要扫描本组件的包""空配置能启动""结果可复现"——它们在
`allocation-spring` 的测试里都被验证过，但每一条都是**在组件自己的测试工程里**验证的：测试用了 `@DynamicPropertySource`、
用了显式路径、用了测试用的规则。真实宿主不会这样，它有自己的包名、自己的 `application.yml`、自己的规则。

**决策**：`allocation-example` 是一个独立的 host 应用，它的包名是 `io.allocation.example`，**不覆盖**
`io.allocation.spring`，因此它用到的每一个引擎 Bean 都必须来自 `AutoConfiguration.imports`；它的 `application.yml`
只写两个目录；它在启动时用自带的两阶段规则跑一遍、打印结果、并对一份冻结的 `results.csv` 做对账。

三条收益：

1. **它是第一条承诺的证据**。如果 starter 哪天开始依赖组件扫描，这个模块会先于任何客户停摆。
2. **它是第三条承诺的证据，并且是持续的证据**。那份 `reference/results.csv` 是这 36 个数字第一次被算出来的样子；
   引擎任何改动只要动了其中一分钱，示例启动就会打印 `status=DIFF`，而不是安静地印出新数字。这与接入清单第 10 步
   是同一个调用，只是比对对象在仓库里。
3. **它替读者跑第一遍**。一个只有端点的示例要求读者先写客户端才能学到东西；这个示例启动即输出总额、前几行、
   以及某个坐标的完整决策链。

**已知代价**：它会被复制。有人会把 `DemoRunner` 复制到自己的工程里，然后抱怨它 `System.out`。这是可接受的——
一段三十行的、看得懂的启动代码，比一个"框架级"的示例生成器更容易被丢弃。文档里明确写了关掉它的开关
（`example.demo=false`）。

**顺带修掉的一个坑**：`await()` 返回的是 `result(runId, 1, 1)`，也就是一页一行。示例最初用它打印"前六行"，
结果只打出一行——接口没错，是调用方假设错了。示例改成先 `await` 再 `result(runId, 1, 6)`，并在代码里写明了原因。

---

## D-026 一页结果只读一页的代价

**状态**：已接受

**背景**：`AllocationTemplate.result(runId, page, size)` 为了给每行附上决策链，先调
`repository.lines(runId)` —— 把**整条账本**读进内存，再在内存里按池分组、重算余数排名。`explain` 同理，为了
一个坐标读整条账本。

在示例的 36 行规模上这是对的，在这个引擎宣称的规模上是堵墙：100 万行的 run，一次翻页要造 100 万个
`LedgerLine` 对象（几百 MB），而调用方要的是 10 行。这与 D-017 修掉的对账问题是同一类缺陷——"读全部"伪装成
一个查询。

**决策**：分两步读，两步都有界。

1. `linesForTargets(runId, targets)`：本页涉及的坐标的账本行。`idx_line_target (run_id, target_key)` 早就存在
   ——索引一直在，只是没人用。JDBC 里按 400 个 key 一批切分，而不是一条 SQL 塞 500 个占位符。
2. `linesForPool(runId, stage, rule, pool)`：这些行所属的**整池**。必须整池，因为一个目标的余数排名是整池的
   属性——只拿它自己那一行算不出 `rank` 和 `gotResidual`。新增 `idx_line_pool (run_id, stage_id, rule_id, pool_key)`。
3. 两步的乘积就是上界：**页大小 × 池宽度**，与 run 的总行数无关。500 行一页、每池 10 个目标，读 5000 行；
   1 万目标一池，读 500 万——后者的瓶颈在池的宽度上，那是规则自己决定的，不是翻页决定的。

**兼容性**：两个新方法在 `RunRepository` 上都是 `default` 实现（过滤 `lines`），宿主自己的仓储实现不会编译失败；
但默认实现仍然读全部，所以文档里写明"在意的人应该覆盖"。

**这条不变量由测试钉住**：`BoundedResultReadTest` 用一个"调用 `lines()` 就抛异常"的仓储包装跑完 `result()` 与
`explain()`。任何人把宽读加回来，测试立刻红，而不是在生产上先 OOM。

**已知代价**：一次翻页从 1 条 SQL 变成 1 + P 条（P = 本页涉及的池数，通常个位数到百位数）。每个都是走索引的
点查，换来的是内存上界从"run 的大小"降到"页的大小 × 池宽"。这是明确的取舍，不是遗漏。

---

## D-027 指标是监听器，不是定时查库的台账

**状态**：已接受

**背景**：P7 的最后一项是"Micrometer 指标：池数、行数、耗时、残余分布、失败原因"。最省事的实现是
`MeterBinder`：`select count(*) from allocation_run group by status`，每次抓取查一次。它在三个方面是错的，
而且都错得很安静：

1. 每次抓取一条聚合查询，实例数 × 抓取频率。P7 自己要求"加依赖就能用"，一个让 Prometheus 每 15 秒打一次
   数据库的 starter 不满足这条。
2. 它报告的是**所有实例**的 run，而指标通常是按实例做容量决策的。多副本部署下"本节点在跑的 run 数"永远读不到。
3. 它**算不出耗时**。状态表知道 run 什么时候结束，不知道它从开始到结束花了多久；`started_at`/`finished_at`
   两个时间戳差可以，但那是把"记录了什么"当成"能算出什么"。

**决策**：指标实现为 `AllocationListener`（`AllocationMetrics`），在 run 自己的线程上记一个数。一次 run 五到八个
计量操作，与 run 本身的成本相比不可见；零查询；天然按实例；`Started → Published/Failed/Cancelled` 两个时间戳
就在手上，耗时是减法。

**顺带的三个设计取舍**：

- **`queue.time` 与 `duration` 分开**。合起来的"总耗时"没法回答运维真正要问的问题：是排队排久了（加 worker /
  提高准入上限），还是跑得慢（优化规则）。分开之后，两个数各自有一个动作。
- **replay 不产生第二次排队时间**。幂等键命中时 `Submitted(replay=true)` 只是"有人在重试"，它不是一个新的等待。
  如果覆盖原有提交时间，重试会伪装成容量压力。
- **终态计数器带零值上线**。三个 `outcome` 的 counter 在构造时就注册为 0：一张只在故障之后才出现的图，在故障
  期间没人敢信。

**刻意不做的三件事**：

- **池级指标**。10 万池的 run 会为了统计自己多分配 10 万个计量对象——为观测付出的代价，超过了被观测的对象。
  池级进度已经在 `Executor.Progress` 里，走 `status()` 读。
- **残余分布**。P7 的原文列了这一项，这里明确不做：要数"多少池拿了余数"，就必须在每池结算循环里加比较——那是
  D-017/D-025 花了两轮才把成本从热路径上挪开的地方。而这个问题是**正确性**问题，不是容量问题：答案在账本每行的
  `got_residual` 与 `explain` 里，审计读一次就够。把它做成时序指标，只会得到一条没人会据此做决定的曲线。
- **分位数与直方图**。宿主开 `management.metrics.distribution.percentiles` 即可；组件替所有部署默认打开，
  就是让每个部署为一个它可能不导出的功能付时钟与环形缓冲区的钱。

**已知代价**：指标只统计**本进程**的 run。多副本部署要看全局，就得靠 Prometheus 侧按 job 聚合（这是它的本职），
或者另一个查库的导出器——但那是宿主的监控体系，不是引擎的职责。文档 §5.1 写明了这一点。

---

## D-028 监听器可以不止一个——而 `@ConditionalOnBean` 差点让它一个都不剩

**状态**：已接受

**背景**：加上指标之后，"容器里有几个 `AllocationListener`"从 0/1 变成了 1/2。原来的装配用的是
`listener.getIfAvailable(AllocationListener::noop)`：两个 Bean 时它抛 `NoUniqueBeanDefinitionException`，
**应用起不来**。一个宿主没要求过的功能，把宿主自己的配置变成启动失败——这是最坏的一类失败。

同时暴露了第二个坑，且它不报错：`AllocationMetricsAutoConfiguration` 最初写的是
`@ConditionalOnBean(MeterRegistry.class)`。这个条件只看得见**先于自己处理**的自动配置注册的 Bean，而
"谁创建 `MeterRegistry`"取决于宿主装了哪个导出后端（actuator 的 `MetricsAutoConfiguration`、
`SimpleMetricsExportAutoConfiguration`、Prometheus……各有各的排序位置）。示例工程里它的实际表现是：

```
PROBE MeterRegistry beans: [simpleMeterRegistry]
PROBE metrics beans: []
```

注册表在，指标一个都没有，日志里没有一个字。`afterName` 指向 `MetricsAutoConfiguration` 也没用——真正创建
`simpleMeterRegistry` 的是排在它**之后**的另一个类。

**决策**：

1. **组合而不是唯一**。`AllocationListener.allOf(List)` 按 Bean 顺序转发，**每个监听器单独 try/catch**。一个
   朴素的 `event -> { a.on(event); b.on(event); }` 在这里是不够的：第一个抛异常，后面的就收不到——"审计表挂了
   顺手把监控也弄瞎"是那种要查一整天的事故。没有监听器时返回共享的 no-op 实例，引擎的引用比较快路径因此仍然
   有效；只有一个时原样返回，不套一层为空的组合器。
2. **不条件化，直接取**。`AllocationMetricsAutoConfiguration` 改成 `ObjectProvider<MeterRegistry>.getIfAvailable()`：
   任何 Bean 开始实例化时，所有定义都已注册，所以注册表存在就会被按需创建，不存在就返回 null——**与自动配置的
   处理顺序无关**。没有注册表时这个 Bean 就是共享的 no-op 监听器（返回类型故意声明为 `AllocationListener`），
   被 `allOf` 丢弃，于是"没装 actuator"的应用回到它原本的状态：一个监听器都没有。

**为什么不是别的东西**：`@ConditionalOnBean` + 更长的 `afterName` 列表是把"宿主装了哪个导出后端"硬编码进组件，
下一个后端出现时又静悄悄地失效；`MeterBinder` 只解决绑定，不解决"运行事件从哪来"；让宿主自己写指标适配层，
就是把这一页的坑交给每个宿主各踩一遍。

**这条不再靠文档保证**：`ExampleApplicationTest` 断言示例应用（真实的 Boot 应用、真实的 actuator）里
`/actuator/metrics/allocation.run.finished?tag=outcome:published` 的计数是 1。上面那个 `metrics beans: []`
的场景，现在会让 `mvn verify` 变红。

---

## D-029 `AmountSource.Custom`：把"实现自己的输入"从建议变成通道

**状态**：已接受

**背景**：`docs/API.md` §6 一直写着"宿主自己实现的输入（JDBC 游标、对象存储流等）应当覆盖
`forEachAmount`/`forEachDriver`"，§1.1 还画了一个 `record Jdbc(String sql, Object... params)` 的接口草图。两处都是
**纸面上的**：`AmountSource` 是 `sealed`，只 permit `Csv` 与 `InMemory`，而 `RunCommand` 只接受 `AmountSource`。
也就是说，一个数据在 MySQL 里的若依宿主，能走的只有两条路：把表导成 CSV，或者把整表 POST 成内存行。两条都在
"数据有一千万行"时崩塌，而这正是这个引擎存在的场景。

一个 sealed 层次恰好只有一个好处：新增变体时编译器会点出所有需要处理的地方。反面是，**没有变体的能力就是不存在
的能力**，即使文档里写了三遍。

**决策**：新增 `AmountSource.Custom(AllocationInput input)`，`inputFor` 直接把它交给执行器。宿主因此可以写一个
游标输入（MyBatis `ResultHandler`、`Stream<Row>`、S3 分块），执行器照旧只调用流式方法。

**同一条决策里最不显然的部分，是 ALLOC_013**。幂等键是一个承诺："同 key 即同数据"。引擎兑现它的方式是
`requestDigest`：规则字节 + 每个数据集的 `fingerprint`。自定义输入可以返回 `null` 作为 fingerprint（接口一直
允许），于是摘要里写 `absent`——此时同 key、同规则、**不同数据**的第二次提交会被判定为 replay，返回上一次的
数字。这是"错误答案伪装成缓存命中"，比报错难查一个数量级，而且它与 D-016 修掉的"哈希目录不哈希内容"是同一个
bug 换了层皮。

所以：**带 key 且没有 fingerprint 的自定义输入，直接拒绝（`ALLOC_013`，HTTP 422）**；不带 key 时照常运行。
选择"拒绝"而不是"接受并记 absent"，是因为前者会让宿主在一分钟内知道要做的事，后者会让宿主在三个月后知道。
这条检查只对 `Custom` 生效——`Csv` 与 `InMemory` 永远有东西可哈希，行为一分不变。

**已知代价**：一条规则读几个数据集，宿主就要为几个数据集提供 fingerprint；要做到"内容摘要"通常意味着第二次
扫描（或维护一个随写更新的摘要列）。这是宿主的选择题——`count+sum+max(updated_at)` 已经在绝大多数业务表上够用
——但引擎不会替它选，也不会在它没选的时候假装一切正常。

**同时修掉的文档债**：§1.1 里那个从未存在过的 `record Jdbc(String sql, Object... params)` 被删掉了，换成真实的三
个变体加一个 §1.2 的完整示例（Mapper 输入、行序为什么必须稳定、重量为什么是 10^6、为什么 `readAmounts` 最好抛
异常）。文档里出现一个不存在的类型，比少写一段说明更有害：读者会照着它写代码，然后以为是自己的问题。

---

## D-030 进度数字必须说自己属于哪个阶段

**状态**：已接受（D-024 里记的待办，本条关闭它）

**背景**：`GET /allocation/runs/{id}` 的 `progress` 是 `{pools, totalPools, rows}`。执行中的数字来自
`Executor.Progress` 回调，回调的签名是 `(stageId, totalPools, completedPools, rows)`——**它一直带着阶段名**，
模板把这个名字丢掉了。

代价是可见的：两阶段规则第一阶段 4 个池跑完报 `4/4`，第二阶段开始报 `0/12`。一个按 `pools/totalPools` 画的
进度条从满回到零，而 run 全程都在前进。同一个字段还有第三种含义：终态的数字来自已提交证据，是**所有阶段相加**
（`poolCount`），与执行中的"当前阶段"不是同一把尺子。

**决策**：`RunView.Progress` 与 `Api.ProgressResponse` 都加上 `stageId`：

| 值 | 含义 |
|---|---|
| 阶段 id | 三个计数只描述这一个阶段 |
| `null` | 三个计数是整个 run 的合计（终态、以及尚未开始的 run） |

计数本身一个都没改。改的是"这些数字属于谁"——这是唯一能在不引入"跨阶段总池数"这个假分母的前提下说清楚
进度的办法。要算跨阶段总量，就必须知道后面每个阶段会切出多少池，而那取决于还没读到的输入，提前编一个数只会
让进度条更不准。

**代价**：`Progress` 是多一个字段的破坏性变更（record 构造器变了）。在 1.0 之前、且唯一的消费者是 REST JSON
（多一个字段是兼容的），这个代价现在付比以后付便宜。`docs/API.md` §2.3 用一张表写清了"什么时候读它、什么时候
不该把两个阶段相减"，因为这是客户端唯一可能用错的地方。

**没有做的事**：没有加"总进度百分比"。百分比需要跨阶段分母，而那个分母在阶段开始前不存在；一个客户端只要记
"上一个 `stageId` 变了"就能自己维护完成度，并且它知道自己的阶段总数。引擎给事实，客户端给修辞。

---

## D-031 租户是存储层的一个 WHERE，不是一个模式开关

**状态**：已接受（D-023 末尾的"已知代价"，本条关闭它的一半）

**背景**：这一条不是"补一个计划中的 SPI"，而是修两个已经在纸面上的缺陷——它们在单租户部署里永远不会出现，
在多租户部署里第一天就出现：

1. **`idempotency_key` 是全局唯一的**。两家公司都把月度批次叫 `2026-01` 是再正常不过的事（甚至可以说，越规范
   的公司越会这么叫）。在多租户若依里，第二家提交时要么撞 `ALLOC_005`，要么——更糟——在 replay 分支里被判定为
   "同一个请求"，把**第一家公司的 run** 作为自己的结果返回。后者不是报错，是错误答案伪装成缓存命中，和 D-016、
   D-029 修掉的是同一个 bug 换了层皮。
2. **读 run 只看 run id**。ULID 带时间前缀，相邻 id 是可猜的；一次日志泄露、一个共享的报表链接、一次"帮我看看
   这个 id"，就是另一家公司的全部成本明细（`/result`、`/explain` 全都开门）。

**决策**：

- `allocation_run` 加 `tenant_id VARCHAR(64) NOT NULL DEFAULT ''`，唯一键改成 `(tenant_id, idempotency_key)`。
  默认值的意义不是"留空好看"：单租户部署下它永远是 `''`，复合键退化成原来那个键，**同一份 SQL 在两种部署下
  都跑**。没有"多租户模式"开关——模式是一个只在一边被测试过的分支。
- 子表（pool / line / result）**不加** `tenant_id`。`run_id` 是全局主键，这些行只能经由一个 run
  到达，而租户在 run 上已经判过了；给百万行明细加一列只承载可推导信息的字符串，是拿存储和写放大换一个已经
  成立的保证。
- 过滤器只有一处：`find(runId)`。引擎里所有读路径——`status` / `await` / `result` / `explain` / `reconcile` /
  `cancel`——都经过 `requireRun`，而 `requireRun` 只调 `find`。一个 WHERE 覆盖七个端点，是因为这七个端点本来
  就已经收敛在一个方法上（这个收敛是 D-026 顺手做出来的，这次才显出价值）。
- `InMemoryRunRepository` 一模一样地实现。宿主对着内存版开发、对着数据库版上线，只在一边存在的过滤器是一段
  上线前永远不会变红的测试。`TenantIsolationContract` 把同一组断言跑两遍，两个实现必须同时绿。

**同一条决策里最不显然的部分是"什么时候读租户"**。引擎在自己的虚拟线程上执行 run，那些线程**不继承**宿主的
`ThreadLocal`。所以：

- 租户在 `submit` 的那一刻、在**提交线程**上被读一次，写进 run 行；worker 只写子表和状态，不再需要它。
- 这也意味着宿主的 MyBatis 租户拦截器帮不上忙：它会在引擎的写线程上找不到上下文（或被写进 `NULL`）。把租户
  交给存储层自己带着走，是这个引擎必须自己做的事，不是可以外包给框架的事。
- 宿主若在自己起的线程池里调用 `submit`，必须自己把租户带过去——这是任何基于 `ThreadLocal` 的框架对异步调用
  的既有要求，不是这个组件新增的。

**`null` 是系统上下文，不过滤**，这条要单独辩护，因为它是"fail open"。批处理任务、启动时把上个进程遗留的
`RUNNING` 钉成 `FAILED` 的清理、运维查账，都没有租户，也都不该被挡住；而"没有上下文"和"上下文解析失败"在
存储层看来是同一件事，唯一能区分它们的地方是边界——`AllocationAuthorizer`。所以：**存储层放宽，边界收紧**。
要"每个请求都必须带租户"的宿主，在 authorizer 里写一行 `if (TenantHelper.getTenantId() == null) throw FORBIDDEN`，
它看得见请求，而 `JdbcRunRepository` 看不见。

**与 D-028 相反的一条**：监听器可以有很多个，租户只能有一个。多个监听器是每个人都观察同一件事；多个租户是
对"这份数据是谁的"给出两个答案，而静悄悄地挑一个就等于把一家公司的账挂到另一家名下。所以 `ObjectProvider`
在这里不用 `getIfAvailable` 的宽容语义——多声明一个 `TenantProvider` 会让应用启动失败，这是**故意**的。

**已知代价**：已经建过表的宿主要执行一次迁移（`ALTER TABLE allocation_run ADD COLUMN tenant_id ... ` + 删掉旧
唯一键 + 建复合唯一键），`ddl/` 里的建表语句只覆盖新库。这是 1.0 之前唯一一次破坏性的 schema 变更，写在这里
比写在发布说明里更可能被读到。另外 `findByIdempotencyKey` 在没有租户上下文时按 `''` 过滤而不是不过滤——键是
一个租户对自己许的诺，不是一个全局命名空间；这个不对称在 `RunRepository` 的方法注释里写了原因。

---

## D-032 找不到就是 404

**状态**：已接受

**背景**：`requireRun` 一直抛 `ALLOC_001`（规则非法 → 400）。这是把两种完全不同的情况压进同一个码：

- "你的规则写错了" —— 调用方要改请求体。
- "这个 run 不存在" —— 调用方要改 id，或者接受它已经没了。

一个按状态码分支的客户端（重试 404 没有意义，修正 422 才有）会被 400 误导到错误的方向上；而一个排障的人看到
"invalid rule" 去查规则，查一下午也不会想到 id 拼错了。

**决策**：新增 `ALLOC_014 RUN_NOT_FOUND` → `404`，`requireRun` 改抛它。同时它成为跨租户访问的答案：**"不是你的"
和"不存在"必须是同一个响应**，否则一个探测者只要对比 404 与 403 就能确认哪些 id 是真的，而 run id 是陌生人
与一家公司成本明细之间唯一的东西。

**已知代价**：对以 `400` 分支的既有客户端是一次行为变更，和 D-031 一起发，因为它们描述的是同一个错误答案的
两个来源。`docs/API.md` §3 的表格里两个码都写着触发条件，`AllocationControllerTest` 钉住"每个码都有 HTTP 状态"
与码的总数（14），加码时漏改文档会变红。

---

## D-033 三份 DDL 必须互相校对，顺手删掉一张没人写的表

**状态**：已接受

**背景**：CI 只能执行一份 DDL——SQLite，因为它是唯一能嵌进 JVM 的。另外两份才是真正部署的（若依跑
MySQL，财务团队跑 PostgreSQL）。让它们保持同步的机制，在此之前**是"有人看一眼"**。那不是机制：在一个文件里
加一列、忘了另外两个，能编译、能过所有测试，然后在客户数据库上、在第一次上线的那一刻失败。

而"有人看一眼"确实漏过：`allocation_run` 加 `tenant_id` 的时候，三份文件都得改，唯一能验证的只有 SQLite。
更早的证据是 `allocation_reconcile`——MySQL 版给它加了自增主键 `id`，另外两份用的是 `(run_id, ref_sha256)`。
同一张表，两种形状，存在了很久，没人发现，因为**没有任何代码写这张表**。

**决策**：

1. `SchemaConsistencyTest` 解析三份文件，逐表比较列名与列序，外加"三份声明的表集合相同"。它证明的是
   "三份文件描述同一个形状"，**不**证明 MySQL 会接受这个语法——那只有服务器能证明，而 CI 没有服务器。这条
   边界写在测试的类注释里，免得下一个人以为它比实际更强。
   它也确实会红：往 PG 的 `allocation_run` 里插一个假列，测试报
   `allocation-postgres.sql disagrees about the columns of allocation_run`，并把两边的列单都打出来。
2. 删掉 `allocation_reconcile`。对账的产物是 `ReconcileOutcome`（计数摘要）与一份 `differences.csv`——一份
   要给人看、要归档的差异文件，不是一行可查询的状态。一张永远没有行的表比没有这张表更糟：它会诱使宿主在它
   上面建报表。删掉之后三份 DDL 各剩五张表，正好是代码写的那五张。

**为什么不做类型比较**：三种方言的类型本来就不该一样（`TEXT` 与 `VARCHAR(1024)`，`INTEGER` 与 `BIGINT`
读出来是同一个 Java 值）。把类型对齐只会让某一方言写得不自然，而抓不到真正的错误——真正的错误是"这一列在
另一个文件里忘了"。

---

## D-034 结束的 run 不数第二遍自己的池

**状态**：已接受

**背景**：`status()` 在终态分支里做的是：

```java
List<PoolRecord> pools = repository.pools(runId);
return new Progress(null, pools.size(), record.poolCount(), record.rowCount());
```

`pools.size()` 与 `poolCount` 是同一个数——`poolCount` 就是发布时用写池行的那同一个计数器写下的。所以这是一次
**为了得到一个已经在手上的数字而做的表扫描**，每轮询一次做一次。一个 10 万池的 run，前端刷新一次页面就拉 10
万行；而 `status` 是这套 API 里被调用最频繁的端点。

顺带它还混了两个来源：`pools` 来自证据、`totalPools` 来自记录。进程在"证据已提交、记录还没更新"那一瞬间被
杀掉之后，这一对数字会变成 **3 / 0**——一个 300% 的进度条。

**决策**：终态时两个计数都取记录里的值，`pools == totalPools == pool_count`。一个结束了的 run 就是它自己的
100%，这在语义上是恒等式，不是近似。

**代价**：一个被杀的进程留下的孤儿证据（上面那个 3 行）不再被计入进度，那个 run 报 0/0。这是有意的：
它已经带着 `ALLOC_012` 标记，没有人会靠它的进度条决定任何事；而"每次刷新都扫一遍证据表"是每个正常结束的
run 都要付的钱。用一件罕见事故的信息量，换掉一件常见操作的开销，方向是对的。

**没有改的一处**：非终态、且本进程没有实时进度的那条分支仍然读证据（`repository.pools(...)`）。那是"另一个
进程正在跑这个 run"或"排队中还没被取走"的场合，除了对方已经提交的行，没有别的数字可看——那里的一次读是
唯一能说真话的东西。

---

## D-035 一个 run 用自己执行时的规则解释自己，而不是用"今天同名的那份"

**状态**：已接受

**背景**：`specOf` 一直是 `SpecCodec.parse(rules.require(record.ruleCode()).json())`。`allocation_run` 存了
`rule_code` 和 `rule_sha256`——前者用于回查，后者是装饰：**记录了执行的是哪份文档，却没记录文档说了什么**。
所以每次读路径（`result` / `explain` / `reconcile`）都按 code 重新查"今天"的规则，用它来给昨天写下的整数
贴标签（precision、维度顺序、坐标）。

这在三种情况下静默地错，而且调用方一个都看不见：

1. **规则被重新发布、只改了 precision**。存储的整数一个都没变，逗号挪了位：precision=2 时写下的 `10000`
   本意是 `100.00`，用 precision=4 的规则读出来变成 `1.0000`。这是错误答案，但看起来完全权威。
2. **维度被重新排序**。坐标按规则声明的维度顺序解析，重排后每一个份额都贴到错误的成本中心上——这是唯一
   比"报错"更糟的失败模式：它不报错。
3. **规则被改名、退役、或从目录里删掉**。它自己的历史随之变得不可读，哪怕 run 与证据完好无损。

而 `rule_sha256` 在那儿，却从没被读。一个存了 digest 却不验证 digest 的系统，比不存 digest 更糟——它营造
了"我们在做完整性"的假象。

**决策**：文档随 run 走，digest 被验证。

- `allocation_run` 加 `rule_json`（TEXT，见 API.md §4 为什么不是 JSON/jsonb 类型），`submit` 时把
  `rule.json()` 原样写进去。`RunRecord` 加一个 `ruleJson` 字段，`specOf` 改为：有 `rule_json` 就
  `sha256(json) == ruleSha256` 验证后 `parse(json)`；没有（旧行）就退回按 code 查当前规则、**同样验证
  digest**。
- 验证失败抛新错误码 `ALLOC_015 RULE_DIGEST_MISMATCH` → `500`（不是 400：请求没问题，是服务端的控制文档
  坏了，重试换个 body 也没用）。答案要么是对的，要么是响亮的错——绝不给出"看起来对的错数字"。

**为什么是 500 而不是别的**：一个按状态码分支的客户端对 500 会重试或上报，对 400 会去改请求体——前者才
是这里该有的动作。`RUN_NOT_FOUND`（404）也不对：那个 run 明明存在，只是它的规则坏了。

**迁移是响亮的，不是静默的**：旧行的 `rule_json` 是 `NULL`。退回路径**仍然验证 digest**，所以一个改过规则
的存量 run 会在升级后第一次被读取时报 `ALLOC_015`，而不是像以前那样静默地用新规则贴旧整数。这正是这次迁移
想要暴露的东西——它一直是个 bug，只是没人读到那一行。

**代价**：每行 run 多存一份规则 JSON（几 KB）。与"用错 precision 给一家公司报出十倍/十分之一的成本"相比，
这个代价可以忽略；而且它换来的是 run 的自包含——一台只有数据库备份的机器也能解释任意一个历史 run，不需要
规则目录还在。

**没有做的两件事**：

- 没有把 `allocation_rule` 表改造成规则的唯一来源——它在本条决策之后被**删掉了**（D-037）：规则目录是当前
  的事实来源（`DirectoryRuleResolver`），`allocation_rule` 表一直没有任何代码写它，和刚删掉的
  `allocation_reconcile` 是同一类"纸面表"。真要有"规则也进数据库"的那天，会先写一个 DB 背书的
  `RuleResolver`，再由它带着一张有读写路径的表回来，而不是留一张空表等宿主在上面建报表。
- 没有给 `result` 加"历史规则不存在就报错的开关"。直接做成无条件验证：一个不验证 digest 的读取路径，就是
  把 D-016（哈希目录不哈希内容）、D-029（幂等键不看内容）同一个 bug 再犯第四次。

---

## D-036 翻页用 keyset 而不是 OFFSET，因为 seq 是稠密的

**状态**：已接受

**背景**：`results` 用 `LIMIT ? OFFSET ?`。OFFSET 分页的代价是 O(offset)：翻到第 N 页，数据库要把前
N-1 页的行全部重新扫一遍再丢掉。对一个能写千万行的组件，这是"第一页 1ms、第 10 万页 40 秒"的退化曲线，
而且前端每一页都在付上一页的利息。

而 `allocation_result` 的主键是 `(run_id, seq)`，`seq` 由单一计数器 `++seq` 分配——**它是稠密的 1..N**，
不是用 ULID 或时间戳生成的稀疏值。稠密意味着"跳过 N 行"和"`seq > N`"是同一件事，所以 keyset 分页在这里
不是近似，是精确等价，而且走主键索引是 O(log N) + O(limit)。

**决策**：契约从 `results(runId, int offset, int limit)` 改成 `results(runId, long afterSeq, int limit)`，
SQL 从 `LIMIT ? OFFSET ?` 改成 `WHERE run_id = ? AND seq > ? ORDER BY seq LIMIT ?`。语义逐字不变——"跳过
afterSeq 行返回 limit 行"——但表述成 keyset，实现才能走索引而不是走扫描。`afterSeq` 从 0 开始，与 offset
同值，所以 `DefaultAllocationTemplate.result` 的 `(page-1)*size` 调用点一行没改（int 自动拓宽为 long）。

**为什么不用"记录上一页最后一个 seq，由调用方传"**：那才是教科书 keyset 的写法，但它要求 `RunResult`
把 `seq` 暴露给客户端、客户端再原样传回来，是对 REST 契约的一次破坏性变更。这里不需要：seq 稠密，offset
本身就是 keyset。用 offset 的调用方零改动，却拿到 keyset 的复杂度——这是稠密 seq 这个已有不变量的红利，
不付接口变更的钱。

**依赖的不变量**：seq 稠密、从 1 开始、按写出顺序单调。这个不变量由 `JdbcRunRepository` 的 `++seq` 与
`EvidenceWriter` 的单一写入保证，并由 `paginationBySeq` 测试钉住（25 行、步长 7、断言拼起来与整表相等）。
谁要引入稀疏 seq（比如给 `allocation_line` 用 ULID 排序键），谁就得先回来改这条。

**顺带**：`resultCount` 仍是 `COUNT(*)`，没有被这条影响——它是 O(log N) 的索引计数，不是分页的一部分。

---

## D-037 删掉 allocation_rule——它是第二张没有读写路径的表

**状态**：已接受

**背景**：D-035 删表前后，`allocation_rule` 是同一类东西的最后一个成员。它有三列专门描述规则文档
（`spec_json` 是 `JSON`/`jsonb` 类型、`spec_sha256`、`precision`），而 D-035 刚刚论证过：把规则存进会被数据库
重排的 JSON 类型列，会让 digest 失效。更基本的是，**没有任何代码读或写它**——`grep` 全仓库，它只出现在两份
schema 测试里，断言它"存在"。

一张表的存在有三个来源：有代码写它、有代码读它、或者它在等未来的某件事。`allocation_rule` 属于第三种，而
第三种恰恰是 D-033 论证过最危险的一类：一张空表会诱使宿主在上面建报表、建代码生成器页面、把它当成"规则的
官方注册处"，而这些都建立在"引擎会维护它"的隐含假设上——引擎不会。

**决策**：三份 DDL 都删掉 `allocation_rule`（及其索引），schema 测试与一致性测试同步。现在表集合就是引擎
真正写的那四张：`allocation_run` / `allocation_pool` / `allocation_line` / `allocation_result`。规则的
唯一事实来源是 `DirectoryRuleResolver` 的目录，规则的不可变历史由 `allocation_run.rule_json`（D-035）承担。

**与 D-033 的差别**：`allocation_reconcile` 是"曾经以为要存、后来发现不用存"（对账产物是文件）；`allocation_rule`
是"曾经以为要存、后来发现没实现"（规则注册表从未落地）。删法相同，理由不同，所以各记一条。

**没有做的事**：没有写一个 DB 背书的 `RuleResolver` 来"填满"这张表。那是功能，不是卫生；用一个从没被要求、
也没人验证过的功能去挽留一张空表，是把"删一张纸面表"的成本转嫁成"维护一个未经验证的规则仓库"。规则进
数据库的诉求真的出现时，从零写一个带读写的 `RuleResolver` 更便宜，而不是给一张空表打补丁。

---

## D-038 用真 MySQL 跑一遍 DDL 和 JDBC 仓库——发现三处方言缺陷并修复

**状态**：已接受

**背景**：从 D-033 起，`SchemaConsistencyTest` 一直在证明"三份 DDL 描述同一个形状"，但它的注释里明写着一句
自认的边界：**它不证明语法**。`allocation-mysql8.sql` 从诞生起就只被逐列比对过，从未被一个 MySQL 服务器执行过；
`JdbcRunRepository` 也只跑在 SQLite 上。这是"三份文件一致"与"部署的那一份能跑"之间那条缝，而这条缝里正好
藏着三处真实缺陷——本机装上 MySQL 8.0.45 后第一次真跑就全暴露了：

1. **`precision` 是保留字**。MySQL 和 PostgreSQL 都保留 `PRECISION`（`CAST(x AS DOUBLE PRECISION)` 及其同类），
   `allocation_run.precision` 这一列在 `CREATE TABLE`、`INSERT`、`SELECT` 里全部报 1064 语法错误。SQLite 不保留
   它，所以 SQLite 测试一路绿灯，把错误留给了部署时刻。
2. **`allocation_pool` 主键超长**。`(run_id, stage_id, pool_key)` 里 `pool_key` 是 `VARCHAR(1024)`，utf8mb4 下
   一列就是 4096 字节，加 `run_id`(32)+`stage_id`(64) 后 4480 字节，远超 InnoDB 的 3072 字节主键上限（1071）。
   主键不能加前缀，所以这不是调长度能救的——`VARCHAR(672)` 才勉强塞得下，而那是给坐标 key 硬安一个它语义里
   没有的长度上限。
3. **`VARCHAR(1024)` 是三份 DDL 里唯一的不一致**。SQLite 和 PostgreSQL 都用 `TEXT`（无上限），只有 MySQL 用
   `VARCHAR(1024)`。这个 1024 是拍脑袋写的，`pool_key` 真实语义是"任意长度的坐标 JSON key"（最多 30 维度、
   每维任意字符串），它不该有任何长度上限。

**决策**：

- **`precision` 列改名 `scale`**。三份 DDL、`JdbcRunRepository` 的 INSERT/SELECT、`rs.getInt("scale")` 一起改。
  不改名的唯一替代是"感知方言加引用符"（MySQL 反引号、PG 双引号、SQLite 随便），那会毁掉 `JdbcRunRepository`
  "一份 SQL 字符串跑三库"的简洁，而且双引号在 MySQL 默认 sql_mode 下是字符串字面量——加错引号比不改更糟
  （静默返回常量而非列值）。`scale` 在三个方言都不是保留字，语义上就是"缩放的小数位数"。
- **`allocation_pool` 主键改 `(run_id, seq)`**，`pool_key` 改 `LONGTEXT`。这跟 `allocation_line`、
  `allocation_result` 已有的 `(run_id, seq)` 脊柱对齐——引擎本来就有单一 `++seq` 惯用法，pool 只是最后一个
  没有享受它的表。`PoolRecord` 的 Java record 不加 `seq`（它是持久化内部细节，读回时按 `ORDER BY stage_id,
  pool_key` 规范序，不依赖 seq）。

**顺带**：kernel 的 `pom.xml` 加 `com.mysql:mysql-connector-j:8.0.33`（**test scope**，Art.1 的零运行时依赖
不变），并新增 `JdbcMySqlTest`——用 `@EnabledIfSystemProperty(named="mysql.url")` 门控，CI 无 MySQL 自动跳过，
有 MySQL 的开发者 `mvn test -Dtest=JdbcMySqlTest -Dmysql.url=... -Dmysql.user=... -Dmysql.password=...` 就能
真跑。它验证的不只是 DDL 能建表，还有幂等、keyset 分页、租户隔离、utf8mb4 坐标往返、状态清扫排序在 MySQL
上全部成立——正是"三份文件一致"永远证明不了的那一层。

**没有做的事**：没有把 MySQL 测试变成 CI 强制的（CI 没有 MySQL，也不能假设它有）。`JdbcMySqlTest` 是 opt-in
的开发期工具，不替代 `SchemaConsistencyTest` 的持续校对——后者防"忘改一份"，前者防"三份都错"，各司其职。

---

## D-039 坐标/池键列不设长度上限——`pool_key` 用 LONGTEXT 而非 VARCHAR(1024)

**状态**：已接受

**背景**：D-038 修主键超长时，根因不是"1024 选小了"，而是"给坐标 key 设长度上限"这件事本身就是错的。
`pool_key`（以及 `allocation_line.pool_key`、`allocation_result.coordinate`）存的是 `Coord.key()`——一个
维度成员的 JSON 数组，一个规则最多声明 30 个维度，每个成员是宿主数据的任意字符串。它天然无上界，任何
硬编码的 `VARCHAR(n)` 都是把"引擎语义"翻译成"数据库事故"：一个 n+1 字符的成本中心，不该让一家公司的
分摊 run 在写库时失败。

**决策**：`pool_key` 在 MySQL 用 `LONGTEXT`（对齐 SQLite/PG 的 `TEXT`）。`coordinate` 同理保持 `VARCHAR(1024)`
的那一份（`allocation_result.coordinate`）这次一并检查——它在 MySQL 是 `VARCHAR(1024)` 但**不在主键里**，
所以没触发 1071；为一致性和去掉同样的上限，它与 pool_key 一样改为 `LONGTEXT`。

**代价**：`LONGTEXT` 不能进主键也不能进普通索引（MySQL 要求前缀）。所以 `allocation_pool` 的幂等主键改走
`seq`（D-038），而 `allocation_line` 的 `idx_line_pool` 已经在用 `pool_key(191)` 前缀索引——前缀截断的是
"找 pool"这个查询的索引，不影响"读回完整 pool_key"（读的是行数据，不是索引）。pool 的"证据只写一次"由
`(run_id, seq)` 主键冲突兜底，丢失的那点"同一 pool 重写"防御由 line 表的 seq 主键冲突补上（同一 run 重写，
line 先撞）。

**没有做的事**：没有给 `allocation_result.coordinate` 加索引。它从不需要按坐标查——`results` 按 `seq` 分页、
`linesForTargets` 按 `target_key` 查 line。给一个没人按它查的列加索引，是给写放大付钱买不存在的读。
