# 性能记录 / Performance Log

> 这里只记**跑出来的数字**，以及复现它的命令。没有数字的承诺不算承诺；没有命令的数字无法复核。

## 环境

| 项 | 值 |
|---|---|
| 机器 | Windows 11，单机，本地 NVMe（一台开发机，未做 NUMA/绑核） |
| JDK | 21（Temurin） |
| GC | `-XX:+UseSerialGC`（对账基准）；引擎基准用默认 ParallelGC |
| 计时 | 进程外 wall clock，**含 JVM 启动**（约 0.15–0.3s），单次运行，无预热 |

单次、冷 JVM 的数字偏保守：真实服务进程预热后通常更快。反过来，单次运行也意味着没有把 GC 抖动摊平——所以不要把这些数字当成上界。

---

## 1. 引擎：1,000,000 行分摊

工作负载：1,000 个池 × 1,000 个目标，`tools/gen_bench.py` 生成。输出与 Go 参考实现**逐字节一致**（五件产物）。

| 实现 | 1 worker | 4 workers | 堆 |
|---|---|---|---|
| Go 参考实现 | 6.18s | 3.88s | — |
| 本实现 | 3.24s | **2.32s** | 256MB |

256MB 是这台设计当前的**真实下限**：192MB 会 OOM。原因不是算术，而是服务层把证据（结果、账本、守恒、血缘）先收在内存里再落盘/落库；改成流式 sink 是 M4 的工作，不是调参能解决的。

---

## 2. 对账：100 万行 / 两侧

数据：`tools/gen_reconcile_bench.py .bench/reconcile 1000000`，每侧 100 万行、18.9MB，参考侧每 10000 行多一分钱（应报 100 处差异）。

| 方式 | 堆 | 耗时 | 结果 |
|---|---|---|---|
| 内存版（`reconcile`） | 64MB | — | **OutOfMemoryError** |
| 内存版（`reconcile`） | 1GB | 2.95s | matched=999900 different=100 |
| 流式（`--streaming`） | **64MB** | **2.21s** | matched=999900 different=100 |

内存版在 64MB 下直接 OOM，是因为它必须同时持有两侧的全部坐标；流式版只要 64MB 就能跑完，而且更快——它从来没有把两张 map 建起来。

---

## 3. 对账：1000 万行 / 两侧

数据：每侧 1000 万行、181MB，参考侧每 10000 行多一分钱（应报 1000 处差异）。

| 方式 | 堆 | 耗时 | 结果 |
|---|---|---|---|
| 流式（32 分片） | 128MB | 22.8s | matched=9999000 different=1000 |
| 流式（32 分片） | 256MB | 20.9s | matched=9999000 different=1000 |

总额：`4999950000.00` vs `4999950010.00`，用 `BigInteger` 精确求和，没有溢出。两侧输入 SHA-256 记录在报告里。

128MB 能跑完 10M，说明峰值内存跟着**最大分片**走而不是跟着输入走：每个分片约 31 万个坐标，32 个分片轮流处理。

---

## 4. 复现

```bash
# 引擎基准（先生成数据）
python tools/gen_bench.py .bench/engine 1000000 1000 1000
java -cp allocation-kernel/target/classes io.allocation.kernel.AllocationCli \
     run .bench/engine/rule.json .bench/engine/out --workers 4

# 对账基准
python tools/gen_reconcile_bench.py .bench/reconcile 1000000
java -Xmx64m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli reconcile \
     --actual .bench/reconcile/actual.csv \
     --reference .bench/reconcile/reference.csv \
     --precision 2 --streaming --out .bench/reconcile/report

# 千万行
python tools/gen_reconcile_bench.py .bench/reconcile-10m 10000000
java -Xmx128m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli reconcile \
     --actual .bench/reconcile-10m/actual.csv \
     --reference .bench/reconcile-10m/reference.csv \
     --precision 2 --streaming --out .bench/reconcile-10m/report
```

`--streaming` 差异时退出码 3，匹配时 0，可以直接挂在 CI 上。

---

## 5. 明确没有测过的

- MySQL / PostgreSQL 的写入吞吐（`JdbcRunRepository` 只验证了正确性，没有压测）。
- Spring 容器与 HTTP 层的开销（starter 尚未实现）。
- 多进程并发提交下的吞吐（有界准入的行为有测试，吞吐没有）。
- 10M 行**分摊**（不是对账）。引擎目前仍受第 1 节的缓冲上限约束，真正的 10M 分摊要等 M4 的流式 sink。
