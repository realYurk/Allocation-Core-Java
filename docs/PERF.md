# 性能记录 / Performance Log

> 这里只记**跑出来的数字**，以及复现它的命令。没有数字的承诺不算承诺；没有命令的数字无法复核。

## 环境

| 项 | 值 |
|---|---|
| 机器 | Windows 11，单机，本地 NVMe（一台开发机，未做 NUMA/绑核） |
| JDK | 21（Temurin） |
| GC | 标注处为准；对账与"深表"基准用 `-XX:+UseSerialGC`（故意用最朴素的 GC，让堆上限说明问题） |
| 计时 | 进程外 wall clock，**含 JVM 启动**（约 0.15–0.3s），单次运行，无预热 |

单次、冷 JVM 的数字偏保守：真实服务进程预热后通常更快。反过来，单次运行也意味着没有把 GC 抖动摊平——所以不要把这些数字当成上界。

---

## 1. 引擎：宽表，1,000,000 行结果

工作负载：1,000 个池 × 1,000 个目标，`tools/gen_bench.py .bench/engine 1000 1000`。输入只有 1,000 行金额、**1,000,000 行驱动**；输出 100 万行。输出与 Go 参考实现**逐字节一致**（五件产物）。

| 实现 | 1 worker | 4 workers | 堆 |
|---|---|---|---|
| Go 参考实现 | 6.18s | 3.88s | — |
| 本实现 | 3.24s | **2.32s** | 256MB |

堆下限的演进（同一台机器，`-XX:+UseSerialGC`）：

| 堆 | 改造前 | 流式执行器之后 |
|---|---|---|
| 128MB | OutOfMemoryError | OutOfMemoryError |
| 192MB | OutOfMemoryError | **PASS（4.21s）** |
| 256MB | PASS | PASS（2.40s） |

这个形状的瓶颈已经不是金额行，而是**驱动表**：100 万行 `DriverRow` 必须常驻，因为分摊要随机访问权重，不能流式。192MB 就是这张表的价钱。深表（金额行多、驱动小）的对比见第 2 节。

---

## 2. 引擎：深表，10,000,000 行输入 @ 64MB

工作负载：`tools/gen_bench.py .bench/scale-10m 100 100 10000000` —— 1,000 万行金额，滚进 100 个池、100 个目标（只有 10,000 行驱动）。这是真实导出最常见的样子：几百万条分录，汇总到几百个成本池。

| 输入行数 | 输入文件 | 堆 | 溢出预算 | 耗时 | 证据体积 | 结果 |
|---|---|---|---|---|---|---|
| 5,000,000 | 230MB | **64MB** | 250,000（默认） | 7.25s | 520MB | PASS，总额精确守恒 |
| 10,000,000 | 460MB | **64MB** | 250,000（默认） | 13.32s | 1,040MB | PASS，总额精确守恒 |
| 10,000,000 | 460MB | **48MB** | 50,000 | 13.23s | 1,040MB | PASS，总额精确守恒 |

行数翻倍、堆不变、耗时近似线性——这就是"峰值跟预算走、不跟输入走"的意思。48MB 那一行说明预算本身也是可调的：容器越小，把 `--max-rows` 调低，多花的只是磁盘 I/O。

守恒值：`101831995000.00`，输入与输出相等，账本/守恒/血缘/成员五件产物齐全（`source_to_pool.csv` 恰好 10,000,001 行 = 表头 + 每一条输入行）。

**改造前这个工作负载跑不起来**：128MB 下连 100 万行金额都会 OOM。差别不在算术，在持有：

- 金额行不再被读成一个 `List<Row>`；`CsvInput` 用 `StreamingCsvReader` 逐条推给执行器。
- 每个阶段缓冲区是 `RowStore`，超过 `maxRowsInMemory`（默认 250,000 行）就把先前与之后的行走同一个编码写到临时文件，顺序不变、可重放。
- 数据集 SHA-256 改为流式摘要（原来是 `Files.readAllBytes`，200MB 的导出文件光算摘要就要 200MB 堆——这是本次压测抓出来的真实缺陷）。

峰值内存现在跟"预算 + 池数"走，不跟输入走。

---

## 3. 对账：100 万行 / 两侧

数据：`tools/gen_reconcile_bench.py .bench/reconcile 1000000`，每侧 100 万行、18.9MB，参考侧每 10000 行多一分钱（应报 100 处差异）。

| 方式 | 堆 | 耗时 | 结果 |
|---|---|---|---|
| 内存版（`reconcile`） | 64MB | — | **OutOfMemoryError** |
| 内存版（`reconcile`） | 1GB | 2.95s | matched=999900 different=100 |
| 流式（`--streaming`） | **64MB** | **2.21s** | matched=999900 different=100 |

内存版在 64MB 下直接 OOM，是因为它必须同时持有两侧的全部坐标；流式版只要 64MB 就能跑完，而且更快——它从来没有把两张 map 建起来。

---

## 4. 对账：1000 万行 / 两侧

数据：每侧 1000 万行、181MB，参考侧每 10000 行多一分钱（应报 1000 处差异）。

| 方式 | 堆 | 耗时 | 结果 |
|---|---|---|---|
| 流式（32 分片） | 128MB | 22.8s | matched=9999000 different=1000 |
| 流式（32 分片） | 256MB | 20.9s | matched=9999000 different=1000 |

总额：`4999950000.00` vs `4999950010.00`，用 `BigInteger` 精确求和，没有溢出。两侧输入 SHA-256 记录在报告里。

128MB 能跑完 10M，说明峰值内存跟着**最大分片**走而不是跟着输入走：每个分片约 31 万个坐标，32 个分片轮流处理。

---

## 5. 证据写入：流式 sink 的堆上限

`RunRepository.EvidenceWriter` 让一次运行的证据边产生边写库，不再先攒成一个内存对象再落库。测法：对 SQLite 写入 N 条 `allocation_result`，`-Xmx64m -XX:+UseSerialGC`，写完后用 `SELECT COUNT(*)` 核对条数。

| 证据行数 | 堆 | 耗时 | 核对 |
|---|---|---|---|
| 200,000 | 64MB | 0.50s | 200,000 |
| 1,000,000 | **64MB** | 1.88s | 1,000,000 |
| 5,000,000 | **64MB** | 9.36s | 5,000,000 |

三行用的是同一个 64MB 堆，行数翻 25 倍耗时线性增长而堆不变——这就是批量提交（每 1000 行一批、每批提交）的直接结果：峰值内存跟着**批次大小**走，不跟着输出规模走。

注意这里测的是**机制**，不是数据库吞吐。SQLite 单文件、`synchronous=OFF`，写吞吐远高于真实 MySQL 事务日志落盘的开销；数字的意义是"堆不涨"，不是"MySQL 能跑多快"。

---

## 6. 复现

```bash
# 宽表：1000 池 x 1000 目标（100 万行输出，100 万行驱动）
python tools/gen_bench.py .bench/engine 1000 1000
java -cp allocation-kernel/target/classes io.allocation.kernel.AllocationCli \
     run .bench/engine/rule.json .bench/engine/out --workers 4

# 同样的宽表，压到 192MB 堆（要跑得动，说明驱动表是唯一的大件）
java -Xmx192m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli run .bench/engine/rule.json .bench/engine/out-192m --workers 4

# 深表：1000 万行金额 -> 100 池 x 100 目标，64MB 堆
python tools/gen_bench.py .bench/scale-10m 100 100 10000000
java -Xmx64m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli run .bench/scale-10m/rule.json .bench/scale-10m/out --workers 4

# 深表：同样的数据，但把溢出预算降到 5 万行（更小的容器）
java -Xmx48m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli run .bench/scale-10m/rule.json .bench/scale-10m/out-48m \
     --workers 4 --max-rows 50000

# 对账基准
python tools/gen_reconcile_bench.py .bench/reconcile 1000000
java -Xmx64m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli reconcile \
     --actual .bench/reconcile/actual.csv \
     --reference .bench/reconcile/reference.csv \
     --precision 2 --streaming --out .bench/reconcile/report

# 千万行对账
python tools/gen_reconcile_bench.py .bench/reconcile-10m 10000000
java -Xmx128m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli reconcile \
     --actual .bench/reconcile-10m/actual.csv \
     --reference .bench/reconcile-10m/reference.csv \
     --precision 2 --streaming --out .bench/reconcile-10m/report

# 证据写入（需要 sqlite-jdbc 与 slf4j-api，它们只在测试作用域里）
CP="allocation-kernel/target/classes:$(find ~/.m2/repository/org/xerial/sqlite-jdbc \
     ~/.m2/repository/org/slf4j/slf4j-api -name '*.jar' ! -name '*sources*' | paste -sd: -)"
javac -cp "$CP" -d .bench/bench-classes tools/bench/EvidenceBench.java
java -Xmx64m -XX:+UseSerialGC -cp "$CP:.bench/bench-classes" EvidenceBench 1000000
```

`--streaming` 差异时退出码 3，匹配时 0，可以直接挂在 CI 上。

---

## 7. 明确没有测过的

- **驱动表的堆上限**。第 1 节说明它就是宽表的唯一瓶颈，但现在没有任何机制让它不常驻。千万行驱动仍是未解问题。
- MySQL / PostgreSQL 的写入吞吐（`JdbcRunRepository` 只验证了正确性，没有压测）。
- Spring 容器与 HTTP 层的开销（starter 尚未实现）。
- 多进程并发提交下的吞吐（有界准入的行为有测试，吞吐没有）。
- 溢写到磁盘时的 I/O 上限（本地 NVMe；网络盘会慢得多）。
- 检查点与断点续算（P9 的另一半），完全未做。
