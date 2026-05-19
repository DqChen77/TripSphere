# TripSphere 论文实验数字汇总

> 生成时间：2026-05-07 16:27:01  |  实验数量：4

## 1. 四象限汇总表

| 实验 ID | 链路 | 象限 | 故障场景 | 总请求 | 错误率 | 成功率 | 降级率(2xx中) | p50(ms) | p95(ms) | p99(ms) | RPS |
|---|---|---|---|---|---|---|---|---|---|---|---|
| `locust-chaina-baseline-high-none-202605071606` | A | baseline-high | — | 148 | 0.0% | 100.0% | 10.1% | 36442 | 48626 | 65673 | 0.5 |
| `locust-chaina-baseline-low-none-202605071601` | A | baseline-low | — | 34 | 0.0% | 100.0% | 11.8% | 37212 | 59408 | 140184 | 0.1 |
| `locust-chaina-fault-high-geocode_latency-202605071…` | A | fault-high | geocode_latency | 130 | 0.8% | 99.2% | 10.1% | 40031 | 59086 | 70201 | 0.4 |
| `locust-chaina-fault-low-geocode_latency-2026050716…` | A | fault-low | geocode_latency | 33 | 0.0% | 100.0% | 3.0% | 39865 | 51388 | 51605 | 0.1 |

## 2. 链路 A — 业务质量指标

| 实验 ID | 象限 | 故障场景 | avg_day_plans | avg_activities | avg_markdown_len | itinerary_saved_rate |
|---|---|---|---:|---:|---:|---:|
| `locust-chaina-baseline-high-none-2026050…` | baseline-high | — | 3.00 | 11.09 | 1747 | 100.0% |
| `locust-chaina-baseline-low-none-20260507…` | baseline-low | — | 3.00 | 10.94 | 1732 | 100.0% |
| `locust-chaina-fault-high-geocode_latency…` | fault-high | geocode_latency | 3.00 | 11.09 | 1773 | 100.0% |
| `locust-chaina-fault-low-geocode_latency-…` | fault-low | geocode_latency | 3.00 | 11.73 | 1853 | 100.0% |

## 3. 降级信号分布（per 实验）

| 实验 ID | 信号名 | 命中次数 | 占降级请求比例 |
|---|---|---:|---:|
| `locust-chaina-baseline-high-none-2026050` | `geocoding_fallback` | 15 | 100.0% |
| `locust-chaina-baseline-high-none-2026050` | `sparse_activities` | 15 | 100.0% |
| `locust-chaina-baseline-low-none-20260507` | `geocoding_fallback` | 4 | 100.0% |
| `locust-chaina-baseline-low-none-20260507` | `sparse_activities` | 4 | 100.0% |
| `locust-chaina-fault-high-geocode_latency` | `geocoding_fallback` | 13 | 100.0% |
| `locust-chaina-fault-high-geocode_latency` | `sparse_activities` | 13 | 100.0% |
| `locust-chaina-fault-low-geocode_latency-` | `geocoding_fallback` | 1 | 100.0% |
| `locust-chaina-fault-low-geocode_latency-` | `sparse_activities` | 1 | 100.0% |

## 4. 关键对比（可直接引用至论文）


### 链路 A（REST 规划链）

- baseline-low 基线：p95 延迟 **59408 ms**，错误率 **0.0%**。
- baseline-high 容量上限：p95 延迟 **48626 ms**，错误率 **0.0%**。
- **fault-high / geocode_latency**：p95 较基线 ↑ **10460 ms**，错误率 ↑ **0.8pp**，降级率 **10.1%**。
- **fault-low / geocode_latency**：p95 较基线 ↓ **8020 ms**，降级率 **3.0%**。

  链路 A 业务质量（baseline-low）：平均 day_plans=3.0，平均 activities=10.9，平均 markdown 长度=1732 字符。
  **geocode_latency** 组：avg_day_plans=3.0（基线 +0.0），avg_activities=11.1（基线 +0.2），itinerary_saved_rate=100.0%。
  **geocode_latency** 组：avg_day_plans=3.0（基线 +0.0），avg_activities=11.7（基线 +0.8），itinerary_saved_rate=100.0%。

## 5. Tempo TraceQL 查询（验证故障命中数）

**locust-chaina-fault-high-geocode_latency-202605071616**
```traceql
    { resource.service.name = "trip-itinerary-planner" && .experiment.id = "locust-chaina-fault-high-geocode_latency-202605071616" }
    { resource.service.name = "trip-itinerary-planner" && .experiment.id = "locust-chaina-fault-high-geocode_latency-202605071616" && .fault.injected = true }
```

**locust-chaina-fault-low-geocode_latency-202605071611**
```traceql
    { resource.service.name = "trip-itinerary-planner" && .experiment.id = "locust-chaina-fault-low-geocode_latency-202605071611" }
    { resource.service.name = "trip-itinerary-planner" && .experiment.id = "locust-chaina-fault-low-geocode_latency-202605071611" && .fault.injected = true }
```

## 6. 原始数据位置

- `artifacts/locust/locust-chaina-baseline-high-none-202605071606`
  - `artifacts/locust/locust-chaina-baseline-high-none-202605071606/quality.csv`
  - `artifacts/locust/locust-chaina-baseline-high-none-202605071606/stats_stats.csv`
- `artifacts/locust/locust-chaina-baseline-low-none-202605071601`
  - `artifacts/locust/locust-chaina-baseline-low-none-202605071601/quality.csv`
  - `artifacts/locust/locust-chaina-baseline-low-none-202605071601/stats_stats.csv`
- `artifacts/locust/locust-chaina-fault-high-geocode_latency-202605071616`
  - `artifacts/locust/locust-chaina-fault-high-geocode_latency-202605071616/quality.csv`
  - `artifacts/locust/locust-chaina-fault-high-geocode_latency-202605071616/stats_stats.csv`
- `artifacts/locust/locust-chaina-fault-low-geocode_latency-202605071611`
  - `artifacts/locust/locust-chaina-fault-low-geocode_latency-202605071611/quality.csv`
  - `artifacts/locust/locust-chaina-fault-low-geocode_latency-202605071611/stats_stats.csv`
