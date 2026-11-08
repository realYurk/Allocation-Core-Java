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

堆下限的演进（同一台机器，`-XX:+UseSerialGC`，4 workers）：

| 堆 | 只有流式执行器 | 执行器 + 列式驱动表 |
|---|---|---|
| 48MB | OutOfMemoryError | OutOfMemoryError |
| 64MB | OutOfMemoryError | **PASS（2.68s）** |
| 96MB | OutOfMemoryError | PASS（2.27s） |
| 128MB | OutOfMemoryError | PASS（2.23s） |
| 192MB | OutOfMemoryError | PASS（2.26s） |
| 256MB | PASS | PASS |

这个形状只有 1,000 行金额，所以瓶颈从来不是金额行，而是**驱动表**：100 万行驱动必须常驻，因为分摊要随机
访问权重，不能流式。原来一行驱动是一个 `DriverRow` + `Coord` + `String[]`，约 90 字节；现在按列存成
`int[维度]` + `long 权重`，同样一百万行约 28 字节/行。**下限从 256MB 掉到 64MB**，耗时还略降（垃圾少了）。
深表（金额行多、驱动小）的对比见第 2 节。

---

## 2. 引擎：深表，10,000,000 行输入 @ 64MB

工作负载：`tools/gen_bench.py .bench/scale-10m 100 100 10000000` —— 1,000 万行金额，滚进 100 个池、100 个目标（只有 10,000 行驱动）。这是真实导出最常见的样子：几百万条分录，汇总到几百个成本池。

| 输入行数 | 输入文件 | 堆 | 溢出预算 | 耗时 | 证据体积 | 结果 |
|---|---|---|---|---|---|---|
| 5,000,000 | 230MB | **64MB** | 250,000（默认） | 7.7s | 520MB | PASS，总额精确守恒 |
| 10,000,000 | 460MB | **64MB** | 250,000（默认） | 14.5s | 1,040MB | PASS，总额精确守恒 |
| 10,000,000 | 460MB | **48MB** | 50,000 | 13.2s | 1,040MB | PASS，总额精确守恒 |

同一份数据在不同时间跑会有 ±10% 的抖动（这台机器同时在编译和写盘），所以看数量级，不要看小数点。

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

## 7. 内核微基准（JMH）：一次分摊的吞吐

前六节的数字都是端到端的 wall clock，含启动、含 IO、无预热。它们回答"这个负载要多久"，不回答"这段代码快不快"。
分摊的热路径只有一个函数——一次池的 `Apportion`——所以单独测它，才能看出改动是让引擎变快还是只是让 JVM 变慢。

```bash
mvn -q -pl allocation-bench -am package
java -jar allocation-bench/target/allocation-bench-1.0.0-SNAPSHOT.jar \
     -f 1 -wi 3 -i 5 -rf json -rff allocation-bench/jmh-baseline.json
```

| 基准 | 目标数 | ops/ms | 折算 |
|---|---|---|---|
| `split`（每次新建缓冲） | 8 | 12,704.5 | 78 ns / 次 |
| `split` | 64 | 586.7 | 1.7 µs / 次 |
| `split` | 512 | 41.0 | 24.4 µs / 次 |
| `splitReusingBuffer`（复用工作区） | 8 | 12,938.9 | 77 ns / 次 |
| `splitReusingBuffer` | 64 | 577.1 | 1.7 µs / 次 |
| `splitReusingBuffer` | 512 | 42.3 | 23.6 µs / 次 |
| `settlementOrder`（余数结算排序） | 8 | 2,721.1 | 368 ns / 次 |
| `settlementOrder` | 64 | 235.7 | 4.2 µs / 次 |
| `settlementOrder` | 512 | 19.8 | 50.5 µs / 次 |

机器：i5-12490F / 16GB / Windows 11 / Oracle JDK 21.0.9，`-f 1 -wi 3 -i 5`（每个参数各自 fork，单线程）。

**读出来的两件事**：

1. **复用缓冲几乎没有收益**（8 目标 12,704 vs 12,939，差异在噪声内；512 目标 41.0 vs 42.3）。TLAB 上的小对象
   分配便宜到测不出来。所以引擎里**没有**"每个 worker 一个工作区"这种东西——计划里写过（P8 第 3 条），
   实测证明它换不来可测的速度，只会换来一个线程安全的负担。这条数字就是不做它的理由。
2. **余数结算比分摊本身慢 4–5 倍**，因为它是排序（`settlementOrder` 每次池排一遍所有目标）。这也是为什么
   它在引擎里只在池边界调一次，而不是每行调一次。任何"每行都结算一次"的改动都会把整体拖慢一个数量级。

**回归门禁**（不在 `mvn verify` 里，见下）：

```bash
python tools/jmh_regression.py allocation-bench/jmh-baseline.json <新跑的 json>
```

任何基准比基线慢 20% 以上则退出码 1。阈值刻意宽：这台机器上的噪声是百分之几，而真正的退化历来是结构性的
（多一次遍历、循环里多一次分配），表现为几十个百分点。卡到 5% 的门禁会在第一次噪声上误报，然后所有人加
`-DskipTests`——那是门禁的死法。

---

## 8. 宽表极限：1000 万行驱动（1000 池 × 10000 目标）

这是驱动表这个"唯一必须常驻"的结构的上限实验。前几节证明的是：**输入行**可以是千万级、堆只要 64MB，
因为行可以溢写；驱动表不行——某个池要分摊，就要随机访问它所有可选目标的权重，所以它只能住在堆里
（列式，D-018：一行是"每维一个成员 id"的 `int[]` 加一个 `long` 权重，成员字符串按维去重）。
一千万行驱动的表本身在几百 MB 量级，堆下限因此上了一个数量级。

```bash
python tools/gen_bench.py .bench/targets-10m 1000 10000     # 输入 1000 行，驱动 1000 万行，规则 5 维
java -Xmx1536m -XX:+UseSerialGC -cp allocation-kernel/target/classes \
     io.allocation.kernel.AllocationCli run .bench/targets-10m/rule.json \
     .bench/targets-10m/out --workers 4
```

| 堆 | 结果 | 耗时 |
|---|---|---|
| 512MB | `OutOfMemoryError`（驱动表装入阶段） | 2.1s 后失败 |
| 1024MB | **跑不完**：10 分钟内未完成，被强杀（GC 抖动，`UseSerialGC` 全堆回收） | >600s |
| 1536MB | PASS，1,000 池 / 10,000,000 行输出，总额 11,848,199,500 一致 | **17.2s** |
| 2048MB | PASS，同上 | 17.2s |

驱动文件 237MB，输出 2.5GB（每行一条结果 + 证据），语料规模与第 1 节的百万目标宽表是同一条曲线上的两点。

**结论**：千万目标可以跑，代价是**约 1.5GB 常驻**，而不是 64MB——这不是缺陷，是"权重必须被随机访问"的
直接后果。1024MB 的失败方式值得记住：它不是 OOM 崩溃，而是**跑不完**，因为驱动表刚好装得下、但留给溢写
缓冲和 GC 的空间不够，于是全堆回收一直追不上分配。容量规划要按 1.5GB 起步，不要按"刚好装下驱动表"来算。

---

## 9. 明确没有测过的

- **超过千万目标的驱动表**。第 8 节把上限推到了 1000 万行驱动 / 1.5GB；再往上仍是同一条线性曲线，
  但那样的"池"已经不是池了，正确做法是改规则而不是加内存。
- MySQL / PostgreSQL 的写入吞吐（`JdbcRunRepository` 只验证了正确性，没有压测）。
- Spring 容器与 HTTP 层的开销（starter 已实现并有真实端口上的功能测试，但**没有吞吐数字**）。
- 多进程并发提交下的吞吐（有界准入的行为有测试，吞吐没有）。
- 溢写到磁盘时的 I/O 上限（本地 NVMe；网络盘会慢得多）。
- 检查点与断点续算（P9 的另一半），完全未做。中断的 run 目前只能被清理成 `FAILED`（`ALLOC_012`），不能续算。
