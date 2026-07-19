# RAG Study Helper — 作品集演示指南

> Mock 模式内置 **Mock Chat + Mock Embedding + Mock Rerank**，无需 `APP_RAG_CHAT_API_KEY` / `APP_RAG_EMBEDDING_API_KEY` 即可走完 **入库 → 检索 → SSE 问答**。

---

## 零密钥 Mock 演示（推荐）

```bash
cp .env.example .env    # 默认 APP_RAG_PROVIDER=mock
./scripts/demo-mock.sh  # Windows: .\scripts\demo-mock.ps1
```

| 步骤 | 操作 | 预期 |
| --- | --- | --- |
| ✓ | `curl http://localhost:8080/api/health` | `"ragProvider":"mock"` |
| ✓ | `curl http://localhost:8080/api/documents` | 至少 1 条内置演示文档 |
| ✓ | Web UI 问：「RAG 中的向量检索是怎么工作的？」 | Mock 流式回答，含参考文档片段 |
| ✓ | 知识库面板上传 `.md` | 上传成功后可基于新文档追问 |
| ✓ | `node scripts/smoke-mock-demo.mjs` | 自动化 smoke 通过 |

内置演示文档位于 `data/docs/`（启动时自动扫描入库）：

- `rag-demo-overview.md` — RAG 概念与链路
- `vector-retrieval-basics.md` — 向量检索与 Rerank
- `query-rewrite-guide.md` — 查询改写与上传说明

---

## 手动 smoke

```bash
docker compose up -d --build
node scripts/smoke-mock-demo.mjs http://localhost:8080
```

上传验收（可选）：

```bash
curl -F "file=@data/docs/rag-demo-overview.md" http://localhost:8080/api/documents/upload
```

---

## 真实 LLM 演示（需 API Key）

编辑 `.env`：

```bash
APP_RAG_PROVIDER=openai
APP_RAG_CHAT_API_KEY=sk-...
APP_RAG_EMBEDDING_API_KEY=sk-...
```

重启 compose 后使用相同 Web UI；Rerank 会调用 SiliconFlow BGE-reranker（或配置的兼容端点）。

---

## Project Hub Profile

```powershell
cd ai-portfolio/docker
docker compose -f docker-compose.profiles.yml --profile rag-study-helper up -d --build
node ../../rag-study-helper/scripts/smoke-mock-demo.mjs http://localhost:18086
```

Hub 默认端口 **18086**（应用）· **18087**（Chroma）。Profile 已默认 `APP_RAG_PROVIDER=mock` 并挂载 `data/docs`。

---

## 相关文档

- [USAGE.md](../USAGE.md) — 日常操作
- [DEPLOYMENT.md](../DEPLOYMENT.md) — 生产部署
- [PERFORMANCE_REPORT.md](../PERFORMANCE_REPORT.md) — 压测（chat 默认不测，避免计费）
