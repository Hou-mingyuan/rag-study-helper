# 性能基线

## 预算

| 指标 | 门槛 |
|---|---:|
| 本地普通读 p95 | <= 300ms |
| 核心本地写 p95 | <= 800ms |
| smoke 业务错误率 | 0 |
| Lighthouse Performance | >= 90 |
| Lighthouse Accessibility | >= 95 |
| Lighthouse Best Practices | >= 95 |

真实模型的首 token、总生成时间和外部 API 成本受供应商影响，不与本地 Mock 预算混为一谈。

## 固定负载

`loadtest/dry_run.py` 自动选择最新的固定评估空间，要求至少 5 份资料。每轮读取 health、readiness、空间、文档和会话；写入部分创建、改名并删除临时会话，确保无残留。

```bash
python loadtest/dry_run.py \
  --base-url http://127.0.0.1:19050 \
  -n 30 -c 4 \
  --output target/acceptance/performance.json
```

找不到固定空间时，先运行：

```bash
node scripts/evaluate-mock.mjs \
  --base-url http://127.0.0.1:19050 \
  --out target/acceptance/evaluation.json
```

## 2026-07-23 最终结果

环境：Windows、Docker Engine 29.6.2、JDK 21.0.8 运行 Java 17 字节码、Node 22.22.0；最终 Docker 镜像为 2 CPU/1GiB；Mock + MySQL 8.4 + Redis 7 + Chroma 0.4.24；固定 5 文档；每轮 30 次迭代、并发 4。

| 操作 p95 | 第 1 轮 | 间隔后第 2 轮 |
|---|---:|---:|
| health | 29.10ms | 32.40ms |
| readiness（含三依赖） | 55.85ms | 35.06ms |
| 空间详情 | 55.79ms | 36.71ms |
| 文档列表 | 55.24ms | 30.58ms |
| 会话列表 | 57.68ms | 41.43ms |
| 会话创建 | 69.43ms | 36.29ms |
| 会话改名 | 67.12ms | 45.35ms |
| 会话删除 | 84.23ms | 42.72ms |

两轮各执行 60 次读写操作，错误数均为 0。最慢读 p95 为 57.68ms，最慢写 p95 为 84.23ms，连续通过 300/800ms 预算。

早期完整负载中，最慢读 p95 为 257.95ms、最慢写 p95 为 443.89ms。优化包括 readiness 并行单飞刷新、文档列表单查询，以及把应用容器从 1 CPU 调整为 2 CPU；当前结果仍只用于固定本地 Mock 回归。

## 页面结果

真实 Chromium `1440x900` 桌面审计：

| 类别/指标 | 结果 |
|---|---:|
| Performance | 100 |
| Accessibility | 100 |
| Best Practices | 100 |
| FCP | 0.5s |
| LCP | 0.6s |
| TBT | 0ms |
| CLS | 0.004 |

报告生成命令使用 Lighthouse 13.4.0；页面同时在 `768x1024` 和 `375x812` 通过无横向溢出与交互热区检查。

## k6 读 smoke

```bash
k6 run loadtest/k6_smoke.js \
  -e BASE_URL=http://127.0.0.1:19050 \
  -e SPACE_ID=1
```

k6 门槛为错误率 0、检查率 1、health/readiness/documents 各自 p95 < 300ms。它不创建数据；完整读写门以 Python 脚本为准。

## 限制

- 以上是单机 Mock 回归，不代表公网或真实模型 SLA。
- Milvus 通过契约和功能 smoke，未完成大规模索引吞吐测试。
- 飞书 fake server 验证了限速/重试/删除保护，但不对真实租户 API 做负载。
- readiness 会主动检查 MySQL、Redis 和向量状态，不应作为高频业务端点调用。
