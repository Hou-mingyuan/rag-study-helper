# RAG Study Helper · 性能与压测基线

> Mock/轻量 HTTP smoke：**不默认对 `/api/chat` 做负载**（会调用 Chat/Embedding/Rerank，产生费用）。

## 目标端点

| 端点 | 方法 | 用途 |
| --- | --- | --- |
| `/api/health` | GET | 探活、Compose smoke |
| `/api/documents` | GET | 列表（轻量 DB 读） |

Hub Profile 基址：`http://localhost:18086`  
独立 Compose 基址：`http://localhost:${APP_HOST_PORT:-8080}`

## 预期延迟（本地 Docker，参考）

| 指标 | p95 目标 | 说明 |
| --- | --- | --- |
| `/api/health` | < 300 ms | 含 DB/Redis 探测 |
| `/api/documents` | < 800 ms | 空库或少量文档 |
| `/api/chat` SSE TTFB | 1–8 s | **视 LLM/向量检索**，不做 CI 压测 |

## smoke-test 基线（与 `--smoke-test` 类比）

| 检查项 | 命令 | 期望 |
| --- | --- | --- |
| 健康 | `curl -sf $BASE/api/health` | HTTP 200，`resCode=200` |
| 文档列表 | `curl -sf $BASE/api/documents` | HTTP 200 |
| 页面 | 浏览器打开 `/` | Web UI 可加载 |

## Python dry-run

```bash
# 独立栈
python loadtest/dry_run.py --base-url http://localhost:8080

# Hub Profile
python loadtest/dry_run.py --base-url http://localhost:18086
```

## k6（可选）

```bash
k6 run loadtest/k6_smoke.js -e BASE_URL=http://localhost:18086
```

## CI 建议

1. `mvn test` — 单元测试
2. `docker compose up -d --build` + `curl /api/health`
3. 可选：`python loadtest/dry_run.py`

## 结果记录模板

| 日期 | 环境 | BASE | health p95 | documents p95 | 备注 |
| --- | --- | --- | --- | --- | --- |
| 2026-07-06 | 独立 Compose（InMemory） | `:18090` | **26 ms** | **30 ms** | `python loadtest/dry_run.py --base-url http://localhost:18090` **PASSED**（12 迭代，并发 3） |
| — | Hub Profile | `:18086` | — | — | 见 [ai-portfolio/docker/verify/rag-study-helper.md](../ai-portfolio/docker/verify/rag-study-helper.md) |

## 限制

- 未覆盖 Embedding 批处理、Milvus 集群扩展、飞书全量同步。
- 接真 LLM 压测前请单独预算与限流监控。
