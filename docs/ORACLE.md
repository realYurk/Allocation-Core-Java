# 黄金语料与双证人机制 / Oracle Strategy

> 目标：**Java 版上线后，Go 完全消失，但"算得和以前一样"这件事仍然可被证明。**

> **落地现状（与本文档设计意图的差异）**：本文档描述的是理想化的 `golden/` + `manifest.json` 结构。
> 实际落地时做了两处收敛，但双证人机制本身完整保留：
> 1. 语料目录实际是 `allocation-conformance/src/test/resources/corpus/`，每个场景一个目录、`.golden/`
>    存期望输出（无 `manifest.json`，指纹由 git 的不可变性承担）。
> 2. 冻结脚本是 `tools/freeze_golden.sh`（调 Go 参考二进制一次），语料生成器是 `tools/gen_corpus.py`；
>    Go 参考实现**未归档进本仓库**，仍留在原 Go 工程。副证人（W2）已实现为
>    `allocation-kernel/.../money/ApportionPropertiesTest`（jqwik 属性 + 百万随机输入 vs 独立 BigInteger oracle）。
> 下面的 `golden/`、`legacy/`、`corpus-gen.jar` 等字样，读作"语料目录""Go 参考实现""语料生成脚本"即可。

---

## 1. 核心思路

把 Go 引擎当作**一次性编译器**，而不是运行依赖：

```
        ┌─────────────────────────────┐
        │  一次性（Phase 0，离线执行）  │
        │  Go 参考实现 + 语料生成器     │
        └──────────────┬──────────────┘
                       │ 产出
                       ▼
        ┌─────────────────────────────┐
        │  golden/ 冻结进仓库          │
        │  输入 + 期望输出 + SHA-256    │
        └──────────────┬──────────────┘
                       │ 永久使用
                       ▼
        ┌─────────────────────────────┐
        │  Java 实现 + 差分测试        │
        │  （CI 每次运行，不依赖 Go）   │
        └─────────────────────────────┘
```

冻结之后：

- `golden/` 是**不可变资产**，只增不改。
- 任何环境（开发者机器、CI、客户现场）都不需要 Go 工具链。
- Go 源码可以删除或归档到 `legacy/`，不再参与构建。

---

## 2. 双证人（two-witness）设计

单一 oracle 有个致命问题：如果 Go 本身在某个边界是错的呢？

因此每个金额断言必须由**两个独立证人**同时确认：

| 证人 | 实现 | 作用 |
|---|---|---|
| **主证人（W1）** | 冻结的黄金语料 | 保证"和过去一致"，覆盖真实业务形态 |
| **副证人（W2）** | Java 独立有理数 oracle | 保证"数学上正确"，覆盖 Go 没想到的边界 |

### 2.1 副证人（W2）的实现要求

**必须用与生产代码不同的算法路径实现**，否则只是自我验证：

- 生产实现：`long` 整除 + 余数排序
- 副证人：`BigInteger` 分数运算，`share_i = total * w_i / Σw`（精确有理数），
  比较时用交叉相乘，不做任何整除；
  然后按"与精确值差的绝对值最小者优先、平局按身份"分配残余。

两条路径在同一输入上必须给出**完全相同的整数结果**。任何不一致都是缺陷（可能出在任一侧）。

W2 只存在于 `src/test`，不进入生产代码。

**W2 的落地**：`allocation-kernel/src/test/java/io/allocation/kernel/money/ApportionPropertiesTest`——
jqwik 属性测试（§6 的 P1–P8）加一个 `fastPathAndGeneralPathAgreeOnMillionRandomInputs`（100 万随机输入
对拍独立 BigInteger oracle）。它用精确有理数重算每个分片、按 UTF-8 字节序平局，与生产 `long` 路径零代码共享。

---

## 3. 语料分类（必须全部覆盖）

| 编号 | 类别 | 覆盖点 | 规模 |
|---|---|---|---|
| C01 | 基础单阶段 | 1 池 × 少量目标 | 20 例 |
| C02 | 尾差密集 | total 为质数、权重互质、目标数 2..1000 | 200 例 |
| C03 | 负数与冲销 | 负金额、负权重（ALLOW_SIGNED）、混合符号 | 50 例 |
| C04 | 边界 | `MinInt64`、`MaxInt64`、0.01、精度 0/6、128 字节文本 | 30 例 |
| C05 | 多阶段 | 2–3 阶段、三级层级、非整除阶段 | 30 例 |
| C06 | 重复坐标 | 金额重复行、Driver 重复行 | 20 例 |
| C07 | 缺数据 | 缺 Driver、缺列、缺目标、零分母 | 20 例 |
| C08 | 层级展开 | 父子成员、include/exclude 组合 | 15 例 |
| C09 | 置换不变 | 同数据 5 种行序 | 由 C01–C06 派生 |
| C10 | 宽表 | 30 维度、长成员名、逗号/引号/中文/emoji | 10 例 |
| C11 | 大规模 | 100 万行 / 1000 池 × 1000 目标 | 2 例（不进仓库，脚本生成） |

### 3.1 目录结构

```
golden/
  manifest.json                # 每例的输入指纹、输出 SHA-256、生成器版本
  c01-basic/
    001-single-pool/
      rule.json
      amounts.csv
      drivers/*.csv
      expected/results.csv
      expected/run_summary.json
  c02-rounding/
    ...
  properties/
    seeds.txt                  # 属性测试的确定性种子
```

### 3.2 manifest 条目

```json
{
  "case": "c02-rounding/017-prime-total",
  "spec": "spec-v1",
  "generator": "gen-v1",
  "inputs": { "rule.json": "sha256:...", "amounts.csv": "sha256:..." },
  "expected": { "results.csv": "sha256:...", "rows": 87, "total": "12345678.90" },
  "levels": ["L1", "L2"]
}
```

---

## 4. 冻结流程（Phase 0 执行一次）

```bash
# 1. 生成输入（不依赖 Go）
java -jar tools/corpus-gen.jar --out build/corpus --cases all --seed 20260101

# 2. 用 Go 参考实现产出期望结果（唯一一次使用 Go）
for d in build/corpus/*/*/; do
  ./legacy/AllocationCore run "$d/rule.json" "$d/expected" --workers 1
done

# 3. 计算指纹并写入 manifest
java -jar tools/corpus-freeze.jar --corpus build/corpus --out golden

# 4. 提交 golden/ 并打 tag
git add golden && git commit -m "chore(oracle): freeze spec-v1 golden corpus"
git tag oracle-spec-v1
```

### 4.1 冻结后的规则

- `golden/**` 的任何修改必须走 `[SEMANTICS]` PR，并提升 `spec` 版本。
- 不允许为了让测试通过而修改 `expected/`。
- CI 校验 manifest 中每个文件的 SHA-256，被篡改即失败。

---

## 5. 验证分级

### L1 语义等价（财务正确性门槛）

- 两侧 `results.csv` **按完整坐标排序后**逐行比对。
- 必须完全一致：坐标集合、金额字符串、行数。
- 同时比对 `run_summary.json` 的 `input_amount` / `output_amount` / `output_rows` / `status`。

### L2 字节等价（回归护栏）

- 仅对**单阶段、无重复坐标**的语料要求 `results.csv` 逐字节一致（含 UTF-8 BOM、引号、换行、行序）。
- 用途：防止"语义没变但格式漂了"。

### L3 证据等价（可选，后期）

- `ledger.csv` 的金额列一致（行序不要求），用于证明"过程也一致"。

**合并门槛：L1 100% 全绿；L2 在标记为 `L2` 的语料上 100% 全绿。**

---

## 6. 属性测试（jqwik）

已实现于 `ApportionPropertiesTest`。覆盖的属性：

| 属性 | 表述 |
|---|---|
| P1 守恒 | `Σ Apportion(total, w) == total`，对任意 total ∈ long、任意 w |
| P2 非负性 | 非负输入下输出非负 |
| P3 单调性 | `w_i <= w_j` 且 total>0 时 `v_i <= v_j + 1`（最大差 1 分） |
| P4 置换不变 | 输入与输出按身份重排后完全一致 |
| P5 幂等 | 同输入两次调用结果相同 |
| P6 残余有界 | `|Σv − total| == 0` 且分配只触及 `|residual|` 个元素 |
| P7 零权重 | `w_i == 0` 时 `v_i ∈ {0, 1}`（可能拿到 +1 的残余） |
| P8 单目标 | `len(w)==1` 时 `v[0] == total`（含 MinInt64） |

P3 与 P7 是"看起来显然但极易写错"的两条，必须保留。

另有 `fastPathAndGeneralPathAgreeOnMillionRandomInputs`：100 万随机输入（含负权重、零权重、
MinInt64/MaxInt64、emoji/CJK 平局）上对拍独立 BigInteger oracle，兑现 `Apportion` 类注释里
"property tests assert they agree on millions of random inputs"的承诺。

---

## 7. 差分测试的执行形态

```
mvn -pl allocation-conformance test
```

- 遍历 `golden/`，对每例执行 Java 引擎，比对期望输出。
- 单例失败输出：坐标、期望值、实际值、该池的权重与分母、余数排名（便于定位）。
- 支持 `-Dgolden.filter=c02*` 增量调试。
- 全量运行目标：**< 60 秒**（不含 C11 大规模）。

---

## 8. 迁移期影子运行（上线后 3–6 个月）

生产环境同时跑 Java 版与 Go 版（Go 版仅作为旁路校验，不产出业务结果）：

- 每天对同一批数据双跑，比对 L1。
- 一致率 100% 连续 30 天后，下线 Go 旁路。
- 任何一天不一致 → 立即熔断，按不一致样本生成新语料，回归修复。

这一步不是"保险"，是**取证**。将来有人质疑"你们换语言有没有算错"，你有 6 个月的每日对账记录。

---

## 9. 反面清单

以下做法会让这套机制失效，禁止：

- 用生产实现自己生成期望值（自我循环）
- 语料只覆盖"能跑通"的 happy path
- 为了修测试而改期望值
- 跳过 L2 只跑 L1（格式漂移会静默存在）
- 把 C11 大规模语料放进 git（20MB+ 的 CSV 会撑爆仓库，用脚本按需生成）
