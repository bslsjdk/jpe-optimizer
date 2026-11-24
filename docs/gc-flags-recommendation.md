# GC 参数选型结论（面向 ZL2 原版 Java 25 / HotSpot）

## 背景
目标：用纯 JVM 参数降低 Java 版 MC 的 GC 卡顿，参数必须是 ZL2 自带
JRE 能用的（HotSpot），且不改 ZL2 本身，只在启动参数层追加。

ZalithLauncher2 官方仓库（ZalithLauncher/ZalithLauncher2, main）核实：
- 内置 JRE 25 = **25.0.5.1**（版本文件 `assets/runtimes/jre-25/version`）
- 解压 `universal.tar.xz` 见 `legal/jdk.hotspot.agent` → **HotSpot JVM**（非 OpenJ9）
- 全仓库搜 `UseG1GC` / `G1HeapRegionSize` = 0 处 → ZL2 不写死收集器，走 HotSpot 默认 = **G1**
- 因此 `-XX:+UseSerialGC` / `-XX:+UseParallelGC` / `-XX:+UseZGC` 等标准 HotSpot flag 都可用

## 测试方法
`scripts/gc/GcStress.java`：模拟 MC 分配形态——海量短命小对象（位置/向量/临时对象，
带 volatile sink 防 JIT 消除）+ 小存活集 + 周期性中等 burst。

CI（`.github/workflows/gc-bench.yml`）在 Temurin HotSpot 25（与 ZL2 JRE 同版本族）上，
用手机级 `-Xms64m -Xmx128m`，每组参数跑 3 轮。CI run 45368183478。

## 结果（128MB 堆，3 轮，取稳定值）
| 参数 | gcTotalMs | worstRoundMs(卡顿体感) | 吞吐 MB/s |
|---|---|---|---|
| 默认 (G1) | 7-9 | 3 | ~9900 |
| **Serial `-XX:+UseSerialGC`** | **3-4** | **1** | **~12600** |
| Parallel `-XX:+UseParallelGC` | 2-4 | 4-5 | ~11700 |
| G1 `-XX:+UseG1GC` | 7-8 | 3-4 | ~9900 |
| G1 tuned | 7-8 | 4 | ~9800 |
| ZGC `-XX:+UseZGC` | 18-23 | 6-8 | ~8700 |

## 推荐
**`-XX:+UseSerialGC`**。理由：
1. worstRoundMs 最低（1ms）——单线程 STW 短且没有后台 GC 线程跟渲染/服务端 tick 抢核，
   这正是手机（核心少）最需要的；
2. 小堆下 GC 总暂停最低、吞吐最高；
3. ZL2 的 HotSpot 25 原生支持，纯 JVM 参数即可，不动启动器。

备选 `-XX:+UseParallelGC`：吞吐接近但单次停顿更长（4-5ms），多核设备可再测。
ZGC 在 128MB 小堆上最差，排除。

## 注意 / 后续
- CI 是 x86，只验证"参数可用 + 趋势"。最终必须在真机 A/B 测试
  （"站着不动" / "来回走动" 两场景，看 1% low 与帧时间 p99）。
- ZL2 追加方式：在版本 JSON 的 `arguments.jvm` 或启动器 JVM 参数框加入该 flag；
  本结论不修改 ZL2 代码。
- 该结论与之前 smoothstep 查表负优化的教训一致：只落地可信测量支持的改动。
