---
name: allocation-java-port
description: 把 Allocation-Core 分摊引擎用 Java 21 重写为可嵌入若依（Spring Boot 3.x/4.x）的组件。当任务涉及分摊算法、Apportion、尾差/最大余数、金额定点计算、规则 DSL、多阶段分摊、逐坐标对账、allocation-kernel/starter 模块、差分测试或黄金语料时使用。关键词：分摊、allocation、Apportion、尾差、余数、定点金额、对账、reconcile、若依、RuoYi、Spring Boot、Java 21、移植、golden corpus。
---

# Allocation Java 组件 · 工作手册

> 这份手册是给**接手这个项目的 AI** 看的。它不解释"分摊是什么"，只规定"你必须怎么干活"。
> 权威顺序：`CONSTITUTION.md` > `SEMANTICS.md` > `PLAN.md` > 本手册 > 代码现状。

---

## 0. 开工前必读（顺序不可跳过）

```
1. docs/java-port/CONSTITUTION.md     ← 15 条铁律，违反即返工
2. docs/java-port/SEMANTICS.md        ← 唯一行为规格，尤其是 §2 Apportion
3. docs/java-port/PLAN.md             ← 你在哪个阶段、当前门禁是什么
```

**没读完这三份就开始写代码，是本次任务失败的最主要原因。**

如果只给你一句话：**金额路径上永远不要出现 `double`、裸 `*`、裸 `/`。**

---

## 1. 五条铁律速查（其它都可以查文档，这五条必须背下来）

| # | 铁律 | 违反后果 |
|---|---|---|
| 1 | 金额只有 `long` 缩放整数；无 `double`/`float` | 静默漂移，对不上账 |
| 2 | 所有 `*` `+` 必须 `multiplyHigh`/`addExact`；分母用 `BigInteger` | 溢出回绕成负数 |
| 3 | 除法只能走 `Apportion`（整除 + 显式残余分配） | 尾差丢钱 |
| 4 | 平局只看业务身份，不看行序/HashMap序/线程序 | 结果不可复现 |
| 5 | 每池守恒断言；失败 fail-closed，不产出半成品 | 部分成功的脏数据 |

---

## 2. 任务路由：你是哪一类活

### 2.1 实现某个阶段（P1–P9）

1. 打开 `PLAN.md` 找到该阶段的**交付物清单**和**门禁**。
2. 只做清单内的事。清单外的东西记到 `docs/java-port/BACKLOG.md`，不要顺手做。
3. 每完成一个子任务，跑该子任务对应的测试，**不要攒到最后一起跑**。
4. 阶段结束时写 `docs/java-port/reports/<phase>.md`（模板见 §6）。

### 2.2 修复一个分摊结果不一致

**严禁**先改代码。按这个顺序：

```
1. 把不一致样本最小化（几行输入能复现？）
2. 写成一个 golden 用例（输入 + 期望输出 = 正确那一侧的值）
3. 让它失败（红）
4. 定位：是 §2 算法？§5 候选构建？§6 透传？§7 守恒？
5. 改代码，让它变绿
6. 跑全量 golden，确认没有把别的地方改坏
```

**如果"正确那一侧"本身可疑**（Go 也可能错），用有理数 oracle 判定，并在 `DECISIONS.md` 记一条。

### 2.3 新增一个规则能力（新 target mode / 新阶段类型）

必须走 `[SEMANTICS]` 流程：

```
1. 改 SEMANTICS.md，写清楚新语义与边界
2. 用 Go 参考实现或手算生成期望输出，冻结成语料
3. 改 DSL record + 编译器校验 + 执行器
4. 差分全绿
```

**禁止**：先写代码再补文档。这个项目里文档是规格，不是注释。

### 2.4 性能优化

1. 先有基线：`mvn -pl allocation-bench jmh:run`，把数字记下来。
2. 改完必须证明**同一语料输出逐字节不变**（`Constitution` 第十二条）。
3. 优化前后差距 <10% 的，不要提交（不值得增加复杂度）。

### 2.5 接口/集成（starter、REST）

1. 先看 `API.md` 的契约，字段只增不改。
2. 自动装配必须 `@ConditionalOnMissingBean`，宿主能全覆盖。
3. 默认配置必须能空跑（不配 `allocation.*` 也能启动）。

---

## 3. 代码地图

```
allocation-kernel/src/main/java/io/allocation/kernel/
├── money/       Money, Scale, Overflow      ← 先看这里，金额类型的唯一入口
├── apportion/   Apportion, Residual         ← 核心算法，与 SEMANTICS §2 一一对应
├── rule/        Spec, Stage, Target, ...    ← DSL 的 Java 表达（record/sealed）
├── plan/        Compiler, Plan, StageGraph  ← 规则 → 可执行计划
├── exec/        Executor, PoolPlanner, DriverIndex, RowBatch
├── evidence/    Ledger, Lineage, Conservation
└── error/       AllocationException, ErrorCode

allocation-conformance/    ← 测试专用：golden 运行器、属性测试、有理数 oracle
golden/                    ← 冻结语料，不可变
legacy/                    ← Go 参考实现归档，只读，不参与构建
```

**依赖方向**：`exec → plan → rule → money/apportion`。反向 import 一律拒绝。

---

## 4. 命令

```bash
# 全量验证（提交前必须绿）
mvn -q verify

# 只跑内核单测（秒级，写代码时高频使用）
mvn -q -pl allocation-kernel test

# 差分测试（golden 语料）
mvn -q -pl allocation-conformance test

# 只跑某一类语料
mvn -q -pl allocation-conformance test -Dgolden.filter="c02*"

# 属性测试（jqwik，会跑几千个随机用例）
mvn -q -pl allocation-conformance test -Dtest=ApportionProperties

# 性能基线
mvn -q -pl allocation-bench jmh:run -Dbench=Apportion

# 内核依赖自检（必须无框架）
mvn -q -pl allocation-kernel dependency:list | grep -E "spring|mybatis|jakarta" # 应无输出
```

---

## 5. 禁止清单（看到自己在做这些，立刻停下）

| 禁止 | 为什么 |
|---|---|
| `double` / `float` 表示金额 | 静默漂移 |
| `total / n` 直接当金额 | 丢尾差 |
| `Math.round(x * 100)` | 引入二进制误差 |
| 裸 `a * b`、`a + b` 作用于金额/权重 | 静默溢出 |
| `parallelStream().forEach()` 写结果 | 结果不可复现 |
| `HashMap` 遍历顺序影响输出 | 不可复现 |
| 为了让测试通过而改 `golden/**/expected/` | 篡改证据 |
| 在 `allocation-kernel` 里 import Spring/MyBatis | 破坏内核独立性 |
| 写前端/页面/菜单 | 超出范围（`Constitution` 第十三条） |
| 用 Lombok | 隐藏 `equals/hashCode` 语义 |
| "顺手"加缓存/抽象/插件机制 | 违反第十四条 |
| 先改代码后补规格 | 规格失效，项目崩坏 |

---

## 6. 每次会话结束必须产出（交接）

在 `docs/java-port/reports/` 下追加或更新当日文件：

```markdown
## <日期> · <阶段> · <一句话主题>

**做了什么**
- 具体到文件与方法

**验证结果**
- 命令 + 通过/失败数
- 性能数字（若有）

**没做完 / 已知问题**
- 明确列出，不要含糊

**下一步**
- 下一个可独立执行的任务（要具体到能直接开工）

**决策变更**
- 有无新增/推翻 DECISIONS 条目
```

**没写交接 = 这次会话白干**，因为下一次接手的人（或 AI）会重新踩一遍坑。

---

## 7. 遇到不确定时的处理顺序

1. **先查 `SEMANTICS.md`** —— 80% 的疑问里面已经有答案。
2. **再查 Go 源码**（`legacy/internal/engine/`）—— 它是行为的事实来源。
3. **跑一次 Go 二进制**看实际输出 —— 比我猜更可靠。
4. **仍然不确定** → 写成最小用例，冻结进 `golden/`，并在报告里标注 `[待确认]`。
5. **绝对不要**用"我觉得应该是这样"来定金额语义。

---

## 8. 质量红线（比完成度重要）

- 一个跑得快但算错一分钱的引擎，价值是**负的**。
- 一个慢 30% 但每分钱都能解释的引擎，可以直接上生产。
- 当你面临"要不要为了进度牺牲确定性"时，答案永远是**不牺牲**，然后把问题写进报告的"未完成"里。

---

## 9. 快速自检（提交前最后 60 秒）

```
[ ] grep -rn "double\|float" 自己改过的文件 —— 只看金额路径，确认无命中
[ ] grep -rn "[^a-zA-Z]a \* b\|amount \*\|weight \*" —— 确认都是 Exact 方法
[ ] 新逻辑有对应的 golden 用例吗？
[ ] SEMANTICS.md 改了吗？（若涉及行为）
[ ] mvn -q verify 绿了吗？
[ ] 交接写了吗？
```
