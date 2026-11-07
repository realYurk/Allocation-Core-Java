# 行为语义规格 / Allocation Semantics Spec

> **本文件是唯一权威。** 代码与本文冲突时，以本文为准；需要改行为时，先改本文（PR 标题加 `[SEMANTICS]`），再改代码，再跑差分。
>
> 所有条目均从 Go 参考实现逐条提取，标注了源码位置。未经差分测试证实的条目标注为 `[待钉死]`。

版本：`spec-v1`（对应 Go 参考实现 `internal/engine` 于 2026-11-07 的行为）
适用精度：`precision ∈ [0,6]`
金额内部表示：`long`（缩放 10^precision 的最小货币单位），后端不限（long / BigInteger 中间值）

---

## 1. 数值域

### 1.1 文本解析（`ParseScaled`, model.go:108）

| 规则 | 值 |
|---|---|
| 允许精度 | `0..6` |
| 最大文本长度 | 128 字节（**trim 之后按字节计，不是字符**） |
| 前后空格 | 允许并 trim |
| 前导 `+` / `-` | 允许 |
| 尾部小数点 | 允许（`"1."` == 1） |
| 空串 | 拒绝 `empty decimal` |
| 小数位超过 precision | **拒绝**，不静默舍入 |
| 超出 int64 缩放后范围 | **拒绝**，不截断 |

必须逐条转成单元测试。特别地：`"1.230"` 在 precision=2 时**必须报错**，不能返回 1.23。

### 1.2 输出格式化（`Money`, model.go:161）

- 固定 p 位小数，无千分位，无科学计数法。
- `MinInt64` 必须正确格式化（不能先取绝对值再溢出）。
- 负数：`-0.01` 而不是 `0.-1`。

### 1.3 禁止

金额路径上出现 `double` / `float` / `Double` / `Float` 即为缺陷。`BigDecimal` 只允许出现在**边界**（请求解析、DB 映射、JSON 序列化），进入内核前必须转成 `long` 缩放整数。

---

## 2. 分摊算法（`Apportion`, model.go:210）

这是整个系统的语义核心。**必须逐行等价实现，不允许"优化算法"**。

```
输入: total (long), weights[] (long), identities[] (String), allowSigned (boolean)
前置: weights 非空; len(weights) == len(identities)

1. denom = Σ weights              // 无界整数，可能超过 long
2. 若任一 w < 0 且 !allowSigned   → 拒绝 "negative driver without explicit permission"
3. 若 denom <= 0                  → 拒绝 "driver denominator must be positive"
4. 对每个 i:
     num      = total × weights[i]        // 无界整数，禁止裸 long 相乘
     q, rem   = truncDivMod(num, denom)   // 向零截断（Go QuoRem 语义）
     vals[i]  = q
     rems[i]  = rem
     allocated += q                       // 溢出即失败
5. residual = total − allocated
6. 若 |residual| > n → 失败 "unexpected rounding residual"
7. order = indices 排序：
     若 residual > 0：按 rems 降序
     若 residual < 0：按 rems 升序
     相等时按 identities 升序（字节序）
8. 循环 i ∈ [0, |residual|)：
     ix = order[i mod n]
     vals[ix] += (residual > 0 ? +1 : −1)
9. 返回 vals
```

### 2.1 必须遵守的细节

- **截断方向**：向零截断，不是向下取整。负数场景下二者不同，这是移植第一大坑。
- **身份串**：调用方传入的是**目标完整坐标的规范化 JSON 键**（`["2026-01","P0","S0","Cost-A","CUR"]`，按 `dimensions` 声明顺序），不是成员名。因此平局裁决实际上是按维度顺序的字典序。
- **余数循环**：`|residual|` 可能大于 n 吗？不会超过 n（步骤 6 已断言），但循环仍按 `i mod n` 写出。
- **不允许**用 Kahan、不允许用先加后减的补偿法、不允许改变残余归属。历史数据的字节可复现性依赖于这个精确过程。

### 2.2 已实测的验收样例

| total | weights | 期望 |
|---|---|---|
| 100 (1.00) | 1,1,1 | 34,33,33（身份 A<B<C） |
| -100 | 1,1,1 | -34,-33,-33 |
| 1 (0.01) | 1,1,1 | 1,0,0 |
| 100 | 3,-1（allowSigned） | 150,-50 |
| MinInt64 | 1 | MinInt64 |
| 101 | 1,1,1 | 34,34,33 |

---

## 3. 池（pool）与分组

- 池 = 按 `stage.source.grouping` 聚合后的键。每个维度取值：
  - `AS_IS` → 保留原值
  - `TOTAL` → 折叠为单一成员（内部标记 `__POOL__`）
- 池内金额 = 该池所有输入行金额之和（**必须是精确整数累加，带溢出检查**）。
- 池的总和进入 `Apportion` 的第一个参数。

---

## 4. 阶段与路由

### 4.1 阶段类型（`Stage.Operation`）

| 值 | 语义 |
|---|---|
| `ALLOCATE`（默认） | 按 allocations 分摊 |
| `MERGE` | 按完整坐标合并；目标行 id 取**首条来源行**；写 lineage `MERGE` 边 |
| `PASS` | 原样透传 |
| `enabled=false` | 等价 PASS，但仍写成员证据 |

### 4.2 多输入路由

- `stage.inputs[]` 中每条边有 `share`（缩放 10^6）。
- 多个上游按 `share` 再分：同一条上游行会被 `Apportion` 按 share 切给多个下游。
- **优化不可改变语义**：单一 `share == 1`（1_000_000）且无非零行时可直接传递切片，但结果必须完全相同。

---

## 5. 候选目标与权重（`candidates`, run.go:471）

这一段最容易被"简化"掉，但它决定了财务结果。

```
对每个池:
  遍历该池的 driver 行:
    target = {}
    for d in dimensions:
      mode = stage.target[d].mode
      SOURCE:         若 driver 行提供了 d 且 != 池的 d → 该行丢弃
                      target[d] = 池的 d
      ELEMENT:        target[d] = 常量 value
      INCLUDE / INCLUDE_EXCLUDE: target[d] = driver 行的 d
    若 w < 0 且 policy != ALLOW_SIGNED → 失败
    按 target 的规范化键分组，权重累加（checkedAdd）
  候选键升序排序
  返回 (targets[], weights[], keys[])   // keys 同时作为 Apportion 的 identities
```

### 5.1 三个必须知道的行为

1. **重复 driver 行按目标坐标求和**，不是取最后一条，也不是取第一条。
   实测：权重 `1` 与 `2` 两行 → 分母 3，结果 60%/60%/... 而非 1/2 或 1/4。
2. **声明的目标缺 driver 权重 → 静默消失**，不报错。
   实测：规则 INCLUDE `S0,S1,S2`，driver 只有 `S0,S1` → 运行 PASS，金额 50/50 分给 S0/S1，S2 无行。
   这是**已知的财务风险**，Java 版必须额外提供 `targetCompleteness` 检查（见 §8.3），且默认开启告警。
3. **一个候选都没有** → 失败 `no eligible drivers`，整个运行 fail-closed。

---

## 6. 来源未命中（source filter）

- 阶段 `source.filters` 不匹配的行**原样透传到下一阶段**（进入 emit 路径），不参与分摊。
- 也就是说：**钱不会丢，会带着原坐标继续往下走**。
- 必须在实现中断言：透传行的金额不做任何修改。

---

## 7. 守恒

每个池分摊结束必须满足：

```
stage.output == pool.total
```

否则整个运行失败（不是警告）。另外：

- `immutable_dimensions` 中的维度在分摊前后必须**逐成员相等**，否则失败。
- `conservation_keys` 同理。
- 全局：`grandTotal == 输入总额`，失败则 `status = FAILED`，**不发布任何结果**。

零值行：`amount == 0` 的目标行**不输出**（results.csv 中不存在该坐标）。

---

## 8. 错误契约

### 8.1 错误必须携带上下文

最小集合：`stage.id`、`allocation.id`、`poolKey`、原始错误。缺任何一项都算实现不完整。

### 8.2 失败必须 fail-closed

任何错误 → 不产生 `results.csv`、不产生 evidence、运行状态 `FAILED`。禁止"部分成功"。

### 8.3 Java 版必须新增的检查（Go 版没有）

| 检查 | 理由 | 默认 |
|---|---|---|
| `targetCompleteness` | 声明目标缺权重被静默丢弃（§5.1.2） | 开启，`WARN`；可配 `BLOCK` |
| `driverCoverage` | 某池完全没有候选 | 已有（`no eligible drivers`） |
| `duplicateDriverRows` | 重复行被求和，通常是导出 bug | 开启，计数上报 |
| `residualAudit` | 每池残余绝对值分布 | 开启，指标上报 |

这些是**纯增量**检查，不改变任何金额；必须可关闭以保持与 Go 结果一致。

---

## 9. 输出契约（用于差分）

| 文件 | 说明 |
|---|---|
| `results.csv` | **带 UTF-8 BOM**；列 = `terminal_stage, row_id, <dimensions...>, amount`；amount 固定 p 位小数 |
| `ledger.csv` | 每池每目标：stage, rule, pool, target, target_row_id, pool_amount, share_amount, driver_weight, driver_denominator, allocated_amount |
| `source_to_pool.csv` | 来源行 → 池的映射与金额 |
| `lineage_links.csv` | 父子行关系 |
| `reconciliation.csv` | 池级守恒证据 |
| `run_summary.json` | 输入/输出总额、池数、行数、status、输入 SHA-256、规则 SHA-256 |

### 9.1 行序

`results.csv` 的行序**不是全局排序**，而是"输入顺序 + 池排序 + 阶段推进"的自然结果 `[待钉死]`。
因此差分测试分两级：

- **L1 语义级**：两侧按完整坐标排序后比对（含金额、行数）。这是财务正确性的门槛。
- **L2 字节级**：仅对单阶段、无重复坐标的语料要求逐字节一致（含 BOM、引号、行序）。

Java 版不追求复刻 Go 的行序；追求的是 L1 全绿 + L2 在子集上成立。

---

## 10. 待钉死清单（差分测试负责）

| 编号 | 待确认 | 由谁钉死 |
|---|---|---|
| T1 | `results.csv` 行序规则 | 单阶段语料 L2 |
| T2 | CSV 引号/转义规则是否等同 RFC4180 | 含逗号/引号维度值的语料 |
| T3 | `MERGE` 后同一坐标多行 id 的取值 | 多输入 MERGE 语料 |
| T4 | 多阶段下 terminal_stage 的赋值 | 三阶段语料 |
| T5 | 空字符串维度成员的处理 | 含空成员语料 |
| T6 | 极多维度（30 个）时的键序 | 宽表语料 |

**规则：T1–T6 未全部转成自动化测试之前，不允许合并任何金额相关代码。**
