# 接口契约与接入指南 / API Contract

> 面向两类读者：① 要在若依工程里调用它的后端开发；② 实现它的 AI。
> 契约一旦发布，字段只增不改；破坏性变更必须走 `[SEMANTICS]` 并升 major。

---

## 0. 实现状态

| 层 | 状态 |
|---|---|
| 服务层（`io.allocation.kernel.service`） | ✅ 已实现：`AllocationTemplate` 的七个方法（含 `cancel`）、幂等键、虚拟线程执行、有界准入、**运行中实时进度**（`status()` 在 run 执行期间返回 `pools/totalPools/rows`，不是只在结束时给数） |
| 对账（`io.allocation.kernel.reconcile`） | ✅ 已实现：坐标级比较、容差、重复策略、科目汇总、差异文件。**服务层与 CLI 都走分片流式实现** `ShardedReconciler`：结果分页读库、参考流式读文件、磁盘分片比较（千万行、128MB 堆，见 `docs/PERF.md`）；内存版 `Reconciler` 只用于小数据与测试 |
| 数据库 schema（`ddl/`） | ✅ 已实现：MySQL 8 / PostgreSQL 15+ / SQLite，SQLite 版本由 `SchemaTest` 实际执行 |
| 存储适配器 | ✅ JDBC（`JdbcRunRepository`，零运行时依赖，由 `JdbcRunRepositoryTest` 与 `JdbcServiceTest` 对真实库验证）；MyBatis 适配器可选，非必需 |
| REST 控制器（§2）与 starter 装配（§6） | ✅ 已实现并**在真实端口上验证**（`AllocationHttpTest`：`@SpringBootTest(RANDOM_PORT)`，宿主包下不扫描本组件，所有 Bean 都来自 `AutoConfiguration.imports`）：`allocation-spring` 七个端点全通（preview / submit / status / result / explain / reconcile / cancel），错误码到 HTTP 状态在 `AllocationExceptionHandler` 里只有一张表，`stageId`/`allocationId`/`poolKey` 随错误响应返回；starter 用 `@ConditionalOnMissingBean` 装配引擎与 Web 层，宿主可整体替换。测试 24 个（`AllocationControllerTest` 19 + `AllocationAutoConfigurationTest` 5），跑真实引擎而非 mock |
| 取消（§2.7） | ✅ 已实现：`AllocationTemplate.cancel(runId)`，池批次边界生效，终态 `CANCELLED` 且**不发布**任何证据；不属于本进程的 run 返回 `false`（`ALLOC_011` 见 §3） |
| 引擎执行器 | ✅ 已实现：金额流式读入、阶段缓冲区按预算溢写磁盘、驱动表列式常驻、池级有序并行；1000 万行金额或 100 万行驱动都在 64MB 堆内可完成（`docs/PERF.md`）。**限制**：驱动表必须整体常驻（列式）——100 万行驱动在 64MB 内可跑，1000 万行驱动需要约 1.5GB（实测，`docs/PERF.md` §8） |

本节以下的 REST 与配置契约**已经定稿**：实现时不得改字段，只能增字段。

---

## 1. Java 侧入口（推荐用法）

```java
@Service
public class CostAllocationService {

    private final AllocationTemplate allocation;   // 由 starter 自动装配

    // ① 试算：同步，单池或小批量，返回明细，不落库
    public Preview preview(String ruleCode, List<AmountRow> rows) {
        return allocation.preview(PreviewCommand.builder()
                .ruleCode(ruleCode)
                .rows(rows)
                .explain(true)          // 返回每行权重/分母/余数
                .build());
    }

    // ② 提交：异步，返回 runId，立即返回
    public String submit(String ruleCode, AmountSource source) {
        return allocation.submit(RunCommand.builder()
                .ruleCode(ruleCode)
                .source(source)                  // 可插拔：CSV / JDBC / 内存
                .idempotencyKey("2026-01-" + ruleCode)
                .build()).runId();
    }

    // ③ 查询 / 等待
    public RunView status(String runId)        { return allocation.status(runId); }
    public RunResult await(String runId, Duration t) { return allocation.await(runId, t); }

    // ④ 对账
    public ReconcileReport reconcile(String runId, Path reference) {
        return allocation.reconcile(ReconcileCommand.builder()
                .runId(runId).reference(reference).tolerance("0").build());
    }

    // ⑤ 解释某一分钱
    public Explain explain(String runId, Map<String,String> coord) {
        return allocation.explain(runId, coord);
    }
}
```

### 1.1 核心类型

```java
public record Money(long units, int scale) {}          // 内核唯一金额类型

public record AmountRow(Map<String,String> coord, Money amount) {}

public sealed interface AmountSource {
    record Csv(Path baseDirectory)                     implements AmountSource {}
    record InMemory(List<AmountRow> rows)              implements AmountSource {}
    record Custom(AllocationInput input)               implements AmountSource {}   // 见 §1.2
}

public record Preview(List<AllocatedLine> lines, Conservation conservation, List<Warning> warnings) {}
public record AllocatedLine(Map<String,String> coord, Money amount, ShareTrace trace) {}
public record ShareTrace(Money poolAmount, long weight, long denominator, int remainderRank, boolean gotResidual) {}
```

### 1.2 数据在表里：`AmountSource.Custom`

宿主的数据通常不在 CSV 里。`AmountSource.Custom` 让宿主把自己实现的 `AllocationInput` 交给 `submit`，
并且**只**走流式方法——`forEachAmount` / `forEachDriver`——所以内存是"一行"，不是"一张表"：

```java
public final class MapperInput implements AllocationInput {

    private final AmountMapper mapper;      // 若依里就是你的 @Mapper
    private final String where;

    @Override public List<Row> readAmounts(String dataset) { throw new UnsupportedOperationException(); }

    @Override public void forEachAmount(String dataset, Consumer<Row> consumer) {
        mapper.streamAmounts(where, rs -> consumer.accept(new Row(
                new Coord(new String[]{rs.getString("plant"), rs.getString("account")}),
                Decimal.parseScaled(rs.getString("amount"), 2),
                new String[]{rs.getString("id")})));
    }

    @Override public List<DriverRow> readDrivers(String dataset, String weightField) { throw new UnsupportedOperationException(); }

    @Override public void forEachDriver(String dataset, String weightField, Consumer<DriverRow> consumer) {
        mapper.streamWeights(where, weightField, rs -> consumer.accept(new DriverRow(
                new Coord(new String[]{rs.getString("plant"), rs.getString("account")}),
                Decimal.parseScaled(rs.getString("weight"), 6))));
    }

    /** 没有它就不能带 idempotencyKey（ALLOC_013）。空实现也行，只要它随数据变。 */
    @Override public String fingerprint(String dataset, String weightField) {
        return mapper.contentDigest(where);   // 例如 count+sum+max(updated_at) 的 sha256
    }
}

allocation.submit(RunCommand.builder()
        .ruleCode("cost-allocation")
        .source(new AmountSource.Custom(new MapperInput(mapper, "period = '2026-01'")))
        .build());
```

三条必须知道的约定：

1. **`readAmounts` / `readDrivers` 不会被调用**，实现成抛异常是最好的写法——它能在任何一次回归里立刻暴露"有人把流式路径改回了读全表"。
2. **行序必须稳定**（主键序、`ORDER BY` 都行）。结果里 `row_id` 会嵌入行序号，顺序变了行号就变，语料对账会红。
3. **重量单位是 10^6**（`DriverRow.weight` 是缩放整数），金额是 10^-precision 的缩放整数，二者都不接受 `double`。

`AmountSource.Custom` 只在 Java 侧可用：HTTP 调用方拿不到 JVM 对象，所以 `POST /allocation/runs` 仍然只接受
`csv` 与 `memory`（§2.2）。这是有意的边界，不是遗漏。

---

## 2. REST 契约

统一前缀 `/allocation`，统一响应包 `{ code, msg, data, traceId }`（与若依 `AjaxResult` 对齐）。
认证交给宿主；本组件只要求 `AllocationAuthorizer` 被实现。

### 2.1 `POST /allocation/preview`

同步试算，限制单次 ≤ 100,000 行（超出返回 `ALLOC_007`，请改用 submit）。

```json
{
  "ruleCode": "cost-center-2026-01",
  "rows": [
    { "coord": { "period":"2026-01", "plant":"P0", "sku":"Unassigned", "account":"Cost-A", "currency":"CUR" },
      "amount": "1234.56" }
  ],
  "explain": true
}
```

响应 `data`：

```json
{
  "lines": [
    { "coord": { "period":"2026-01","plant":"P0","sku":"S0","account":"Cost-A","currency":"CUR" },
      "amount": "370.37",
      "trace": { "poolAmount":"1234.56","weight":28,"denominator":182,"remainderRank":1,"gotResidual":true } }
  ],
  "conservation": { "input":"1234.56", "output":"1234.56", "balanced": true },
  "warnings": [ { "code":"W_TARGET_MISSING_DRIVER", "message":"目标 S2 在 Driver 中无权重，已被排除" } ]
}
```

### 2.2 `POST /allocation/runs`

```json
{
  "ruleCode": "cost-center-2026-01",
  "source": { "type":"csv", "path":"/data/2026-01/amounts.csv" },
  "idempotencyKey": "2026-01-cost-center",
  "options": { "workers": 4, "retainLines": false, "failOnMissingTarget": false }
}
```

`202 Accepted` → `{ "runId": "01J9...", "status": "PENDING", "idempotentReplay": false }`

重复提交同一 `idempotencyKey`：返回同一 `runId`，`idempotentReplay=true`，**不重算**。

### 2.3 `GET /allocation/runs/{runId}`

```json
{ "runId":"01J9...", "status":"RUNNING",
  "progress": { "stageId":"allocate-dept", "pools": 620, "totalPools": 1000, "rows": 620000 },
  "startedAt":"2026-11-07T14:00:00Z", "elapsedMs": 3200 }
```

`status ∈ PENDING | RUNNING | PUBLISHED | FAILED | CANCELLED`

`progress` 在 run 执行期间是**实时**的：`totalPools` 在分摊开始前就已确定，`pools` 每完成一个池（更准确说：
每一批并发池）推进一次，都在执行器自己的循环里取数，不是估算。run 到终态后这些数字来自它已提交的证据
（`pools` = 池级对账行数，`rows` = 结果行数），因此重启后仍然可读——实时进度本身不做持久化，它是"此刻为真"的数字。

**`stageId` 是必须读的字段**，因为它说明三个计数属于谁：

| 取值 | 含义 |
|---|---|
| 阶段 id（如 `allocate-dept`） | 三个数只描述**这一个阶段**。多阶段规则换阶段时 `pools` 会从满回到 0，`totalPools` 换成新阶段的分母——这是换了一个口径，不是进度倒退 |
| `null` | 三个数是**整个 run** 的合计（所有阶段）。终态时一定是这一种：数字来自已提交的证据，没有"当前阶段"可指 |

一个阶段内部的进度条请用 `pools / totalPools`；跨阶段的总体进度请自己记"哪个阶段完成了"（或数
`status` 之后的总行数），**不要**把不同 `stageId` 的两个 `pools` 相减。

### 2.4 `GET /allocation/runs/{runId}/result?page=1&size=500`

> **实现说明**：翻页只读本页涉及的账本（先按 `target_key` 取本页的行，再按池取整组以还原余数排名），
> 内存上界是"页大小 × 池宽度"，与 run 总行数无关（D-026）。`POST .../explain` 同理。

分页返回已发布明细；未 `PUBLISHED` 的 run 返回 `ALLOC_006`。

### 2.5 `POST /allocation/runs/{runId}/reconcile`

```json
{ "reference": { "type":"csv", "path":"/data/reference/2026-01.csv" },
  "tolerance": "0",
  "duplicatePolicy": "AGGREGATE" }
```

响应：

```json
{ "status":"DIFFERENCES", "matched": 999987, "amountDiffered": 8, "missing": 3, "extra": 2,
  "differenceFile": "/var/allocation/reconcile/01J9/a1b2c3d4e5f6a7b8/differences.csv" }
```

差异文件落在 `reconcile/<runId>/<参考文件 SHA-256 前 16 位>/` 下：路径由"比了什么"决定，而不是由调用次数
决定。同一个 run 对同一份参考重复对账会得到同一份报告，而对不同参考对账会各自留下报告，不会互相覆盖。

服务层的对账走的是**分片流式实现**（结果分页读库、参考文件流式读、磁盘分片比较），不是内存版：千万行
的 run 在服务里也能对，不需要先把结果集读成一个 List。

### 2.6 `GET /allocation/runs/{runId}/explain?period=...&plant=...&sku=...`

返回该坐标的完整决策链：来源池 → 权重 → 分母 → 余数排名 → 是否获得残余 → 生效规则 SHA-256。

### 2.7 `POST /allocation/runs/{runId}/cancel`

协作式取消：设置取消令牌，池边界检查，已完成的池保留但不发布。

```json
{ "runId":"01J9...", "accepted": true, "status": "RUNNING" }
```

`accepted=false` 表示没有可取消的东西，只有三种情形：run 已到终态、run 不存在、run 不属于当前进程（上一个进程留下的
`RUNNING` 记录无法被协作停止——这种情况需要 P9 的中断恢复，而不是一个会撒谎的成功响应）。`accepted=true`
表示停止指令已受理，run 最终会落到 `CANCELLED`；`status` 回显受理时刻的观察值。

---

## 3. 错误码

| 码 | HTTP | 含义 | 触发 |
|---|---|---|---|
| `ALLOC_001` | 400 | 规则非法 | schema/维度/阶段校验失败 |
| `ALLOC_002` | 400 | 金额格式非法 | 精度超限、溢出、非法字符 |
| `ALLOC_003` | 422 | 守恒失败 | 池级或全局不平 |
| `ALLOC_004` | 422 | 无可用 Driver | 某池候选为空 |
| `ALLOC_005` | 409 | 幂等冲突 | 同 key 但规则/输入不同 |
| `ALLOC_006` | 409 | 结果未发布 | 查询未 PUBLISHED 的 run |
| `ALLOC_007` | 413 | 请求过大 | preview 超行数上限 |
| `ALLOC_008` | 504 | 运行超时 | 超出 `allocation.job.timeout` |
| `ALLOC_009` | 500 | 内部错误 | 未分类异常（必须带 traceId） |
| `ALLOC_010` | 403 | 无权限 | `AllocationAuthorizer` 拒绝 |
| `ALLOC_011` | 409 | 已取消 | 调用方取消；不是故障，run 状态为 `CANCELLED` |
| `ALLOC_012` | 409 | 进程中断 | 持有该 run 的进程在完成前退出；`RunRecovery.abandonInterrupted` 把 `RUNNING`/`PENDING` 记录钉成 `FAILED` + 本码。**不会作为 HTTP 错误返回**——它是 run 记录上的失败原因，调用方从 §2.3 的 `status` 看到 `FAILED` |
| `ALLOC_013` | 422 | 幂等键无法验证 | `AmountSource.Custom` 的输入没有返回 fingerprint，却又带了 idempotencyKey。拒绝而不是"当作一样"：同 key 不同数据时返回上一次的答案，是错误答案伪装成缓存命中（见 §1.2） |

**规则**：错误响应必须含 `stageId` / `allocationId` / `poolKey`（若适用），否则视为实现不完整。

---

## 4. 数据库 DDL

DDL 是代码，只保留一份，放在 `ddl/`：

| 文件 | 目标 |
|---|---|
| `ddl/allocation-mysql8.sql` | MySQL 8（若依默认） |
| `ddl/allocation-postgres.sql` | PostgreSQL 15+ |
| `ddl/allocation-sqlite.sql` | 本地与测试；由 `SchemaTest` 在 CI 中实际执行 |

六张表：`allocation_rule` / `allocation_run` / `allocation_pool` / `allocation_line` /
`allocation_result` / `allocation_reconcile`。

`allocation_result` 存的是最终答案（一条终端输出行），`allocation_line` 存的是它怎么来的
（每条分配决策的权重、分母、份额）。前者供分页查询，后者供 `explain` 与审计。两者都按 `seq` 升序，
`seq` 就是引擎的写出顺序，分页翻过去不会重排。

坐标在 `allocation_result.coordinate` 里存成**一个 JSON 数组**，而不是每个维度一列：规则可以声明
1..30 个维度，固定列集不存在；存引擎自己的规范键，读回来的字符串和现在算出来的逐字节相同。

**金额约定**：所有 `*_amount` 列都是「10^-precision 个最小单位」的整数，precision 来自规则（0..6）。
`123456` 在 precision=2 时表示 `1234.56`。用 `BIGINT` 而不是 `DECIMAL`，是为了让数据库里存的值和
引擎里算的值是同一个整数，搬运过程中不可能发生二次舍入。

**幂等**：`allocation_run.idempotency_key` 上的唯一约束就是幂等的实现，不是缓存。并发提交同 key 时，
数据库只允许一个赢，另一个读到赢家。

**幂等键的"同一次请求"由内容决定**：`request_sha256` = 规则字节 + 每个数据集的内容摘要（不是路径，
不是大小，不是时间戳）。所以重新导出一份数字变了的 `amounts.csv` 再提交，会得到 `ALLOC_005` 而不是
上一次的旧答案；原样重写同样的字节则仍然是重放。

**时间列是 BIGINT 纪元毫秒**，不是 `DATETIME`：三个方言必须能走同一份 JDBC 代码，而时区语义属于
展示层，不属于账本。

**明细可选**：`retainResultLines=false` 时只写 `allocation_pool`（池级证据），`allocation_line` 留空。
超过 500 万行建议关闭明细，保留归档文件。

## 5. 配置项

**已实现（`AllocationProperties`，前缀 `allocation`，全部有默认值）**：

```yaml
allocation:
  working-directory: allocation-data   # run 的证据目录（staging + 原子改名发布）
  rules-directory: allocation-rules    # <ruleCode>.json 所在目录
  workers: 1                           # 默认池级并行度；单次运行可覆盖
  max-rows-in-memory: 250000           # 阶段缓冲区溢写阈值，决定峰值堆
  queue-capacity: 16                   # 准入上限，满了返回 ALLOC_007
  recover-interrupted-runs: false      # 启动时把上个进程遗留的 RUNNING/PENDING 钉成 FAILED(ALLOC_012)
  metrics:
    enabled: true                      # 有 MeterRegistry 时上报运行指标；见 §5.1
```

`recover-interrupted-runs` 默认关闭：只有"一个进程独占数据库"时，"早于本次启动"才等于"没人管了"。
单实例部署（绝大多数若依场景）可以打开；多实例部署打开它，会把另一个实例正在跑的 run 在纸面上判死。

### 5.1 指标（可选，需要 actuator 或 micrometer）

加上 `spring-boot-starter-actuator` 就自动生效，**不需要任何配置**；不加就没有指标 Bean（`allocation.metrics.enabled=false`
是给"加了 actuator 但不想要这些序列"的部署用的）。指标来自 `AllocationListener`，不是每次抓取去查库。

| 指标 | 类型 | 标签 | 含义 |
|---|---|---|---|
| `allocation.run.submitted` | counter | `rule` | 被接受的提交（含 replay） |
| `allocation.run.replayed` | counter | `rule` | 幂等键命中，没有产生新 run。持续上涨是客户端在重试，不是负载 |
| `allocation.run.started` | counter | — | 真正开始执行的 run |
| `allocation.run.finished` | counter | `outcome`=`published\|failed\|cancelled` | 终态分布 |
| `allocation.run.failure` | counter | `code`=`ALLOC_0xx` | 失败原因；与上一条的 `outcome=failed` 是同一件事的两个切面 |
| `allocation.run.queue.time` | timer | `rule` | 提交 → 开始。**这个数涨了就意味着加 worker 或压队列** |
| `allocation.run.duration` | timer | `rule` | 开始 → 终态，不含排队 |
| `allocation.run.pools` / `.rows` | distribution | — | 每次发布 run 的池数与结果行数 |
| `allocation.run.amount` | distribution | — | 每次发布 run 的输出金额，单位是内核的整数最小单位 |
| `allocation.run.in.flight` | gauge | — | 本进程内已提交未终态的 run |

三条刻意的缺席，都在 D-027 里写了原因：**池级指标**（10 万池的 run 会为了统计自己多分配 10 万个对象）、
**残余分布**（"多少池拿了余数"是正确性问题，答案在账本的 `got_residual` 与 `explain`，不在监控里）、
**分位数/直方图**（要就在宿主侧开 `management.metrics.distribution.percentiles`，组件不替所有部署决定这笔开销）。

`rule` 是域名，`code` 是错误码，两者都是部署期常量；run id 永远不做标签。

**未实现（写在这里只是说明曾经的计划，配了也不会生效）**：`job.timeout`、`job.cancel-poll-interval`、
`storage.*`、`integrity.*`、`limits.*`。对应的能力目前在别处：并行度是 `workers`，明细保留是每次提交的
`options.retainLines`，preview 行数上限是 `PreviewCommand.MAX_ROWS`（常量，不配置），精度上限由规则 schema
本身约束（0..6）。

---

## 6. 扩展点（SPI）

| 接口 | 默认实现 | 现状 | 宿主可替换 |
|---|---|---|---|
| `AmountSource` | 规则目录下的 CSV / 调用方内存行 | ✅ 已实现 | 数据从哪来；数据在库里的宿主用 `Custom`（§1.2） |
| `RunRepository` | 有 `DataSource` 走 JDBC，否则内存 | ✅ 已实现 | 存储实现（MyBatis Mapper 亦可） |
| `RuleResolver` | `DirectoryRuleResolver`（`allocation.rules-directory`） | ✅ 已实现 | 规则从库/配置中心来 |
| `AllocationInput` | `CsvInput` | ✅ 已实现 | 覆盖流式的 `forEachAmount` / `forEachDriver`，用 `AmountSource.Custom` 交给 `submit` |
| `Executor.Progress` | 无操作 | ✅ 已实现 | 运行期进度（池数/行数）的外部上报 |
| `AllocationListener` | 无操作 | ✅ 已实现 | 运行事件（提交/开始/发布/失败/取消）；**可以声明多个**，按 Bean 顺序组合 |
| `AllocationMetrics` | 有 `MeterRegistry` 时自动注册 | ✅ 已实现（`allocation-spring`） | 见 §5.1；宿主声明同类型 Bean 即可接管 |
| `AllocationAuthorizer` | 全部放行 | ✅ 已实现（`allocation-spring`） | 接若依 `@PreAuthorize` |
| `TenantProvider` / `DataScopeProvider` | 固定值 / 不限制 | ⬜ 计划中 | 接若依租户与 `@DataScope` |

SPI 原则：**默认实现必须能在空配置下工作**；宿主替换不应触碰内核代码。标 ⬜ 的一项在实现前不发布接口，
因为一个没有默认行为、也没有调用点的接口，只会让宿主提前依赖一个还会变的名字。

**事件的粒度**：`AllocationListener` 只报运行级事件，不报池级事件——一次 10 万池的运行会产出 10 万条事件，
而池级进度已经由 `Executor.Progress` 以计数器形式提供（热路径上读一个数，不分配对象）。事件在**运行自己的
线程上**回调，实现必须快且不得抛异常；引擎会捕获并记日志，所以一个坏监听器不会把成功的运行变成失败的运行。

**多个监听器是一等场景，不是例外**：宿主有自己的审计钩子，而 starter 可能自带一个（指标）。`allocation-spring`
把容器里的所有 `AllocationListener` 用 `AllocationListener.allOf` 组合后交给引擎，**每个监听器单独 try/catch**
——否则第一个抛异常的监听器会让后面的收不到事件，而"审计表挂了导致监控也瞎了"是最难查的那类故障。只有一个
监听器时不做包装，一个都没有时传共享的 no-op 实例（引擎按引用比较，跳过整个通知路径）。

**鉴权在边界，不在内核**。`AllocationAuthorizer` 是 `allocation-spring` 里的装饰器（`AuthorizingAllocationTemplate`），
在调用引擎之前检查，所以被拒绝的请求不会建 run、不读规则、不开连接；顺带保证了"无权读"与"不存在"返回同一个
403，不泄露 run id 是否存在。内核不认识"调用者"，编造一个它无法验证的身份比承认边界在这里更糟。
**进程内调用不受此项约束**：宿主自己调用 `AllocationTemplate` 的代码已经被宿主信任，需要同一套规则就自己调
`check` —— 那一行代码知道调用者是谁，而这个接口不知道。

> **内核级输入契约（已实现）**：`AllocationInput` 除了返回列表的 `readAmounts` / `readDrivers`，还提供
> `forEachAmount(dataset, consumer)` 与 `forEachDriver(dataset, weightField, consumer)`。执行器只走流式
> 这两条。宿主自己实现的输入（JDBC 游标、对象存储流等）**应当覆盖它们**——默认实现会把整表读进 `List`，
> 正确性一分不差，堆会差一个数量级。这样的输入通过 `AmountSource.Custom` 交给 `submit`（§1.2）；在这条
> 通道补上之前，§6 的这句建议在 Java 侧是做不到的，因为 `AmountSource` 只接受 CSV 目录和已经解码好的行列表。金额行流式读入后进可溢写的 `RowStore`；驱动行流式读入后进列式
> `DriverTable`（驱动不能溢写，因为分摊要随机访问权重）。
> 运行期内存预算：`Executor.Options.maxRowsInMemory`（默认 250,000 行，超过即溢写临时文件），
> 命令行对应 `--max-rows N`。

---

## 7. 接入若依的 30 分钟清单

> 想先看它长什么样：`mvn -pl allocation-example spring-boot:run`。那个工程是一个包名不覆盖本组件的宿主应用，
> 空配置启动、两阶段规则、启动即打印总额与决策链，并对一份冻结结果做对账（`allocation-example/README.md`）。

1. `mvn install` 组件（或用企业私服坐标）
2. 在业务模块 `pom.xml` 加入 `allocation-spring`（自带 `spring-boot-starter-web` 传递依赖；只要引擎不要
   REST，就只引 `allocation-kernel`）
3. 执行 `ddl/allocation-mysql8.sql`（4 张表）
4. `application.yml` 加 `allocation:` 段（可全部省略）
5. 准备规则 JSON：放进 `allocation.rules-directory`，文件名即规则码（`<ruleCode>.json`）；
   从库里读规则则声明一个 `RuleResolver` Bean
6. 实现（可选）`AllocationAuthorizer` Bean → 关联若依权限；声明之后引擎会被自动包一层，七个端点全部经过它：

   ```java
   @Bean
   AllocationAuthorizer allocationAuthorizer() {
       return (action, runId) -> {
           String permission = switch (action) {
               case PREVIEW, SUBMIT -> "allocation:run:add";
               case READ, EXPLAIN -> "allocation:run:query";
               case CANCEL -> "allocation:run:cancel";
               case RECONCILE -> "allocation:run:reconcile";
           };
           if (!SecurityUtils.hasPermi(permission)) {
               throw new AllocationException(ErrorCode.FORBIDDEN, "缺少权限 " + permission);
           }
       };
   }
   ```
7. 用若依代码生成器，对 `allocation_run` / `allocation_pool` 生成管理页面
8. 调用 `POST /allocation/preview` 验证一个池
9. 调用 `POST /allocation/runs` 跑全量，轮询 `GET /allocation/runs/{id}`
10. 与旧系统结果 `POST .../reconcile`

**验收标准**：第 8 步在 30 分钟内返回正确明细；第 10 步 `status=MATCH`。

---

## 8. 版本兼容承诺

| 项 | 承诺 |
|---|---|
| Spring Boot | 3.2+ / 4.x 均可（不锁版本，只依赖 `jakarta.*`） |
| JDK | 21（最低 17，若使用 17 需关闭虚拟线程特性） |
| MyBatis | 3.5.x；同时提供无持久化模式（纯内存） |
| 数据库 | MySQL 8 / PostgreSQL 15+；Oracle 提供 DDL 但不承诺性能 |
| 与 Go 版兼容 | `spec-v1` 语料下 L1 全绿、L2 在单阶段子集全绿 |
