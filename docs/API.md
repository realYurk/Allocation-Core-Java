# 接口契约与接入指南 / API Contract

> 面向两类读者：① 要在若依工程里调用它的后端开发；② 实现它的 AI。
> 契约一旦发布，字段只增不改；破坏性变更必须走 `[SEMANTICS]` 并升 major。

---

## 1. Java 侧入口（推荐用法）

```java
@Service
public class CostAllocationService {

    private final AllocationTemplate allocation;   // 由 starter 自动装配

    // ① 试算：同步，单池或小批量，返回明细，不落库
    public Preview preview(String ruleId, List<AmountRow> rows) {
        return allocation.preview(PreviewCommand.builder()
                .ruleId(ruleId)
                .rows(rows)
                .explain(true)          // 返回每行权重/分母/余数
                .build());
    }

    // ② 提交：异步，返回 runId，立即返回
    public String submit(String ruleId, AmountSource source) {
        return allocation.submit(RunCommand.builder()
                .ruleId(ruleId)
                .source(source)                  // 可插拔：CSV / JDBC / 内存
                .idempotencyKey("2026-01-" + ruleId)
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
    record Csv(Path path)                          implements AmountSource {}
    record Jdbc(String sql, Object... params)      implements AmountSource {}
    record InMemory(List<AmountRow> rows)          implements AmountSource {}
}

public record Preview(List<AllocatedLine> lines, Conservation conservation, List<Warning> warnings) {}
public record AllocatedLine(Map<String,String> coord, Money amount, ShareTrace trace) {}
public record ShareTrace(Money poolAmount, long weight, long denominator, int remainderRank, boolean gotResidual) {}
```

---

## 2. REST 契约

统一前缀 `/allocation`，统一响应包 `{ code, msg, data, traceId }`（与若依 `AjaxResult` 对齐）。
认证交给宿主；本组件只要求 `AllocationAuthorizer` 被实现。

### 2.1 `POST /allocation/preview`

同步试算，限制单次 ≤ 100,000 行（超出返回 `ALLOC_007`，请改用 submit）。

```json
{
  "ruleId": "cost-center-2026-01",
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
  "ruleId": "cost-center-2026-01",
  "source": { "type":"csv", "path":"/data/2026-01/amounts.csv" },
  "idempotencyKey": "2026-01-cost-center",
  "options": { "workers": 4, "retainLines": false, "failOnMissingTarget": false }
}
```

`202 Accepted` → `{ "runId": "01J9...", "status": "PENDING", "idempotentReplay": false }`

重复提交同一 `idempotencyKey`：返回同一 `runId`，`idempotentReplay=true`，**不重算**。

### 2.3 `GET /allocation/runs/{runId}`

```json
{ "runId":"01J9...", "status":"RUNNING", "progress": { "pools": 620, "totalPools": 1000, "rows": 620000 },
  "startedAt":"2026-11-07T14:00:00Z", "elapsedMs": 3200 }
```

`status ∈ PENDING | RUNNING | PUBLISHED | FAILED | CANCELLED`

### 2.4 `GET /allocation/runs/{runId}/result?page=1&size=500`

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
  "differenceFile": "/var/allocation/reconcile/01J9/differences.csv" }
```

### 2.6 `GET /allocation/runs/{runId}/explain?period=...&plant=...&sku=...`

返回该坐标的完整决策链：来源池 → 权重 → 分母 → 余数排名 → 是否获得残余 → 生效规则 SHA-256。

### 2.7 `POST /allocation/runs/{runId}/cancel`

协作式取消：设置取消令牌，池边界检查，已完成的池保留但不发布。

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

**规则**：错误响应必须含 `stageId` / `allocationId` / `poolKey`（若适用），否则视为实现不完整。

---

## 4. 数据库 DDL（MySQL 8，若依默认）

```sql
CREATE TABLE allocation_rule (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(32)  NOT NULL DEFAULT '000000',
  rule_code     VARCHAR(64)  NOT NULL,
  rule_name     VARCHAR(128) NOT NULL,
  schema_version VARCHAR(32) NOT NULL,
  spec_json     JSON         NOT NULL,
  spec_sha256   CHAR(64)     NOT NULL,
  status        VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
  create_by     VARCHAR(64),
  create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_rule (tenant_id, rule_code, spec_sha256)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE allocation_run (
  id              VARCHAR(32)  NOT NULL,
  tenant_id       VARCHAR(32)  NOT NULL DEFAULT '000000',
  rule_id         BIGINT       NOT NULL,
  rule_sha256     CHAR(64)     NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status          VARCHAR(16)  NOT NULL,
  input_amount    DECIMAL(28,6) NOT NULL DEFAULT 0,
  output_amount   DECIMAL(28,6) NOT NULL DEFAULT 0,
  pool_count      INT          NOT NULL DEFAULT 0,
  row_count       BIGINT       NOT NULL DEFAULT 0,
  engine_version  VARCHAR(32)  NOT NULL,
  failure_code    VARCHAR(16),
  failure_msg     VARCHAR(512),
  started_at      DATETIME(3),
  finished_at     DATETIME(3),
  create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_idem (tenant_id, idempotency_key),
  KEY idx_status (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE allocation_pool (
  id           BIGINT      NOT NULL AUTO_INCREMENT,
  run_id       VARCHAR(32) NOT NULL,
  stage_id     VARCHAR(64) NOT NULL,
  pool_key     VARCHAR(512) NOT NULL,
  pool_amount  DECIMAL(28,6) NOT NULL,
  output_amount DECIMAL(28,6) NOT NULL,
  delta        DECIMAL(28,6) NOT NULL DEFAULT 0,
  target_count INT NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_run (run_id, stage_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE allocation_line (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  run_id      VARCHAR(32) NOT NULL,
  stage_id    VARCHAR(64) NOT NULL,
  target_key  VARCHAR(512) NOT NULL,
  amount      DECIMAL(28,6) NOT NULL,
  weight      BIGINT,
  denominator BIGINT,
  got_residual TINYINT(1) NOT NULL DEFAULT 0,
  PRIMARY KEY (id),
  KEY idx_run (run_id, stage_id),
  KEY idx_coord (run_id, target_key(191))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE allocation_reconcile (
  id          BIGINT      NOT NULL AUTO_INCREMENT,
  run_id      VARCHAR(32) NOT NULL,
  ref_sha256  CHAR(64)    NOT NULL,
  status      VARCHAR(16) NOT NULL,
  matched     BIGINT      NOT NULL DEFAULT 0,
  differed    BIGINT      NOT NULL DEFAULT 0,
  missing     BIGINT      NOT NULL DEFAULT 0,
  extra       BIGINT      NOT NULL DEFAULT 0,
  report_path VARCHAR(512),
  create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  KEY idx_run (run_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

**PostgreSQL 适配**：`JSON` → `JSONB`，`DATETIME(3)` → `TIMESTAMPTZ`，`AUTO_INCREMENT` → `GENERATED BY DEFAULT AS IDENTITY`。

**注意**：明细表是可选的。大结果（> 500 万行）建议 `retainLines=false`，只保留池级证据 + 归档文件。

---

## 5. 配置项

```yaml
allocation:
  enabled: true
  engine-version: 1.0.0
  job:
    workers: 4                  # 池级并行度
    queue-capacity: 16
    timeout: 30m
    cancel-poll-interval: 500ms
  storage:
    retain-lines: true
    retain-pools: true
    archive-threshold: 5000000  # 超过则只存归档文件
    archive-dir: /data/allocation/archive
  integrity:
    fail-on-missing-target: false   # 声明目标缺权重时是否阻断
    warn-on-duplicate-driver: true
    verify-input-sha256: true
  limits:
    preview-max-rows: 100000
    max-dimensions: 30
    max-precision: 6
```

全部有默认值，**不配置也能启动**。

---

## 6. 扩展点（SPI）

| 接口 | 默认实现 | 宿主可替换 |
|---|---|---|
| `AmountSource` | CSV / JDBC | 数据从哪来 |
| `DriverSource` | CSV / JDBC | 权重从哪来 |
| `RunRepository` | MyBatis | 存储实现 |
| `AllocationAuthorizer` | 全部放行 | 接若依 `@PreAuthorize` |
| `TenantProvider` | 固定 `000000` | 接若依租户/数据源 |
| `DataScopeProvider` | 不限制 | 接若依 `@DataScope` |
| `AllocationListener` | 无操作 | 运行事件（开始/池完成/发布/失败） |

SPI 原则：**默认实现必须能在空配置下工作**；宿主替换不应触碰内核代码。

---

## 7. 接入若依的 30 分钟清单

1. `mvn install` 组件（或用企业私服坐标）
2. 在业务模块 `pom.xml` 加入 `allocation-spring-boot-starter`
3. 执行 `ddl/allocation-mysql8.sql`（4 张表）
4. `application.yml` 加 `allocation:` 段（可全部省略）
5. 准备规则 JSON，插入 `allocation_rule`（或用 `POST /allocation/rules`，见下）
6. 实现（可选）`AllocationAuthorizer` → 关联若依权限
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
