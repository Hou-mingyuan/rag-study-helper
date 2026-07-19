# RAG Study Helper · 十维审计报告

> **审计日期**：2026-07-06（**Round-7 十维复测** · project-hub-1 CI smoke-mock + 检索片段高亮）  
> **范围**：`rag-study-helper`（Spring Boot 2.6 · LangChain4j 0.35 · Chroma/Milvus/InMemory · Redis · 飞书同步 · 内置 Web UI）  
> **评分**：1–10 分  
> **关联**：[PRODUCTION-READINESS.md](../../ai-portfolio/PRODUCTION-READINESS.md) · [PERFORMANCE_REPORT.md](../PERFORMANCE_REPORT.md) · [GAP-MATRIX §rag-study](../../ai-portfolio/docs/GAP-MATRIX.md)

---

## 总览

| 维度 | 得分 | 等级 | Round-6 变更 |
| --- | ---: | --- | --- |
| 1. 文档与 README | **9** | 优秀 | — |
| 2. Docker 与部署 | **8** | 良好 | — |
| 3. CI / CD | **9** | 优秀 | △8→9（docker-smoke + **smoke-mock-demo.mjs** ✅ Round-7） |
| 4. 性能与压测 | **8** | 良好 | Hub **18086 dry_run ✅**（health **30 ms** · docs **56 ms** · project-hub-1 复验） |
| 5. 安全基线 | **8** | 良好 | △7→8（`ApiKeyAuthFilter` 首期 ✅） |
| 6. 测试与质量 | **8** | 良好 | △6→8（**21** JUnit cases ✅ Round-7 +2 snippet） |
| 7. API 与架构 | **9** | 优秀 | — |
| 8. 前端 UX | **9** | 优秀 | SSE **retrieval** 事件 + 检索片段高亮 + 来源文档跳转 ✅ Round-7 |
| 9. 演示与作品集 | **9** | 优秀 | — |
| 10. 可维护性与工程化 | **7** | 良好 | JDK8 锁定升级债 |
| **加权平均** | **8.4** | **作品集就绪+** | **7.7 → 8.0 → 8.2 → 8.4**（Round-7 P1） |

**结论**：九仓中 **Round-6 专项复测对象**（原 GAP-MATRIX 最低 **7.7**）。**LangChain4j 全链路 RAG + 飞书 Wiki 同步 + Mock 零密钥** 仍是核心差异化。Round-5/6 已闭合 **D5 API Key** 与 **D6 核心单测**。**Round-7 project-hub-1**：CI docker-smoke 增加 `node scripts/smoke-mock-demo.mjs`（Mock 问答闭环）· SSE `retrieval` 事件推送 top-5 片段 · 前端 `<mark>` 高亮查询词 + 点击跳转 ChunkPreview。**剩余硬缺口**：JDK17 RFC。

---

## Round-7 变更摘要

| 维度 | Round-6 矩阵 | Round-7 复测 | 依据 |
| --- | ---: | ---: | --- |
| D3 CI | **8** | **9** | `mvn test` + docker-smoke + **`smoke-mock-demo.mjs`** ✅ |
| D6 测试 | **8** | **8** | **`mvn test` 21 cases**（+2 `RagQueryServiceSnippetTest`） |
| D8 UX | **8** | **9** | SSE `retrieval` 事件 · 片段 `<mark>` 高亮 · 点击跳转 ChunkPreview |
| **均分** | **8.2** | **8.4** | +0.2（Round-7 P1 smoke-mock + 检索高亮） |

---

## Round-6 变更摘要（历史）

| 维度 | Round-4/5 矩阵 | Round-6 复测 | 依据 |
| --- | ---: | ---: | --- |
| D3 CI | △7 | **8** | `mvn test` + docker-smoke 绿；`smoke-mock-demo.mjs` 未入 CI |
| D4 性能 | △7 | **8** | Hub `:18086` dry_run PASSED（20/36 ms · project-hub-2）· `:8082` Mock 栈复验 |
| D5 安全 | △7 | **8** | `ApiKeyAuthFilter` + `X-API-Key`（默认关闭） |
| D6 测试 | △6 | **8** | **`mvn test` 19 cases** 本地 2026-07-06 全绿 |
| D8 UX | △7 | **8** | ChunkPreview 面板 + 文档列表点击预览 ✅ |
| D10 工程 | △7 | **7** | JDK8 债未消；文档与 Test 类已对齐 |
| **均分** | **7.7** | **8.2** | +0.5（P2 dry_run + ChunkPreview） |

---

## 1. 文档与 README（9/10）

### 现状

- README 含 ASCII 架构图、快速开始、LLM/向量库/飞书/API/限流全章节。
- 四要素表（零密钥 Mock、演示会话、启动、入库/问答）+ 链至 `docs/DEMO.md`。
- `USAGE.md`、`DEPLOYMENT.md`（含 Hub 18086）、`SECURITY.md`、`PERFORMANCE_REPORT.md`。
- JDK 8 / LangChain4j 0.35 / Chroma 0.4.24 版本锁定说明清晰。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| P2 | README 增加 **Mermaid sequenceDiagram**（chat SSE 全链路） |
| P2 | 飞书同步配置截图 + 常见 403/tenant 错误排查 |
| P3 | 与 `docs/csdn/04-rag-study-helper.md` 交叉链接 |

---

## 2. Docker 与部署（8/10）

### 现状

- 三套 Compose：`docker-compose.yml`（InMemory）、`docker-compose-chroma.yml`、`docker-compose-milvus.yml`。
- Hub Profile **18086/18087/13308/16379** 与 `DEPLOYMENT.md` 对齐。
- `Dockerfile` 多阶段构建（Maven build + JRE 8 runtime）。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| P2 | Compose 为 `rsh-app` 补 `healthcheck` 与 `depends_on: service_healthy` |
| P2 | 生产示例：仅内网暴露 + 反代 TLS 模板 |
| P3 | JDK 17 升级路径文档（Spring Boot 3 + LC4j 0.36+） |

---

## 3. CI / CD（8/10）

### 现状

- `.github/workflows/ci.yml`：`mvn test` + **docker-smoke** job（compose up + health/documents curl + **`smoke-mock-demo.mjs`** Mock 问答闭环）。
- README CI badge。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| ~~**P1**~~ | ~~CI smoke 增加 `node scripts/smoke-mock-demo.mjs`~~ ✅ Round-7 |
| P2 | PR 路径过滤（仅 Java/docs 变更时跑全量 compose） |
| P3 | 镜像 Trivy 扫描 job |

---

## 4. 性能与压测（8/10）

### 现状

- `loadtest/k6_smoke.js`、`loadtest/dry_run.py` 针对 `/api/health`、`/api/documents`。
- **实测（2026-07-06）**：独立 Compose `:18090` — health p95 **26 ms**、documents **30 ms**（12 iter · PASSED）。
- **Hub Profile `:18086`（project-hub-1 复验）**：health p95 **30 ms**、documents **56 ms**（12 iter · 2 并发 · PASSED）；verify-all remediation **1/1 OK**（失败时输出 `DryRunCmd` + `start-profile` 指引）。
- **Hub Profile `:18086`（project-hub-2 终验）**：health p95 **32 ms**、documents **47 ms**（12 iter · PASSED）。
- 明确 **不对 `/api/chat` 做 CI 压测**（LLM 费用边界清晰）。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| ~~**P1**~~ | ~~Hub `:18086` dry_run 实测写入 PERFORMANCE_REPORT~~ ✅ project-hub-2 |
| P2 | k6 50 VU 只读端点压测写入报告 |
| P3 | Mock 模式下 chat TTFB 基线（无费用） |

---

## 5. 安全基线（8/10）

### 现状

- `SECURITY.md`：密钥策略、**API Key 首期**（`ApiKeyAuthFilter` + `X-API-Key`）、限流、上传 50MB 上限。
- `@RateLimit` + Redisson 令牌桶（IP + 日配额）。
- `.env.example` 含 `APP_API_KEY_ENABLED` / `APP_API_KEY`；**默认关闭**，零密钥 Mock 不受影响。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| ~~**P1**~~ | ~~**API Key Header** 最小实现~~ → `ApiKeyAuthFilter` ✅ Round-5 |
| P2 | Redis 不可用时 fail-closed 可配置（当前 fail-open 限流） |
| P3 | 上传 MIME 白名单 + 飞书 Secret 轮换文档 |

---

## 6. 测试与质量（8/10）

### 现状

- **Round-7 复验**：`mvn test` **21 cases**（0 fail，2026-07-06 本地）  
  `ResultsTest` · `QueryRewriteServiceTest` · `RerankServiceTest` · `FeishuWikiSupportTest` · `ApiKeyAuthFilterTest` · `RagProviderResolverTest` · **`RagQueryServiceSnippetTest`**
- CI 依赖 `mvn test` + Docker smoke + **`smoke-mock-demo.mjs`**。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| ~~**P1**~~ | ~~RAG/Rerank/Feishu 核心单测~~ → **21 cases** ✅ Round-7 |
| ~~**P1**~~ | ~~`smoke-mock-demo.mjs` 纳入 CI docker-smoke~~ ✅ Round-7 |
| P3 | Testcontainers：MySQL + Redis 集成测试 |

---

## 7. API 与架构（9/10）

### 现状

- 完整 RAG Pipeline：查询改写 → Embedding → 向量 top20 → BGE Rerank top5 → Prompt → SSE 流式。
- 多向量后端（InMemory / Chroma / Milvus）、OpenAI 兼容 LLM、统一 `Results<T>` 响应。
- 飞书 Wiki 定时同步、SHA256 去重、Redis 会话、Redisson 分布式限流。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| P2 | OpenAPI/Swagger 导出 |
| P3 | 混合检索（BM25 + 向量）Roadmap |
| P3 | 引用溯源 `[1][2]` 角标 |

---

## 8. 前端 UX（8/10）

### 现状

- 单文件 `static/index.html`：SSE 流式、Markdown、会话/知识库面板、深/亮主题、移动端适配。
- **Round-6 ChunkPreview**：`GET /api/documents/{id}/chunks` + `#chunkPreviewPanel` 分块预览（文档列表点击即显）。
- **Round-7 检索片段高亮**：SSE `event: retrieval` 推送 top-5 片段 · 查询词 `<mark>` 高亮 · 点击片段跳转来源文档 ChunkPreview。
- 非 Vue/React SPA，但功能完整、零构建依赖。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| ~~P2~~ | ~~上传进度与分块预览 UI~~ ✅ Round-6（`ChunkPreview` API + `#chunkPreviewPanel`） |
| ~~P2~~ | ~~检索片段高亮与来源文档跳转~~ ✅ Round-7 |
| P3 | 空库/限流 429 统一 Toast 文案 |

---

## 9. 演示与作品集（9/10）

### 现状

- Mock **零密钥**：`APP_RAG_PROVIDER=mock` + `data/docs/` 种子 + `demo-mock.ps1/.sh`。
- `scripts/smoke-mock-demo.mjs` 自动化 smoke。
- Hub **18086** verify 文档就绪；CSDN [04-rag-study-helper.md](../../docs/csdn/04-rag-study-helper.md) 就绪。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| P2 | 录屏：首问 → SSE 流式 → 知识库上传追问 |
| P3 | 飞书同步 live demo（需测试 tenant 凭证） |

---

## 10. 可维护性与工程化（7/10）

### 现状

- `CHANGELOG.md`、`VERSION`、`.env.example`、Hub 端口矩阵一致。
- LangChain4j **0.35 / JDK 8** 锁定有文档理由，但形成**技术债**。
- Round-6：README 测试类清单与 `src/test/java` **已对齐**。

### 剩余 gap

| 优先级 | 动作 |
| ---: | --- |
| ~~**P1**~~ | ~~对齐 README 与 Test 类~~ ✅ Round-6 |
| P2 | `mvn verify` 聚合脚本 + pre-commit 格式检查 |
| P3 | JDK 17 分支或升级 RFC（Spring Boot 3 迁移） |

---

## 优先行动清单（Top 8 · Round-6 后）

| # | 优先级 | 动作 | 维度 |
| ---: | ---: | --- | --- |
| 1 | ~~**P1**~~ | ~~CI 增加 `smoke-mock-demo.mjs`~~ ✅ Round-7 | D3 **9** |
| 2 | ~~**P1**~~ | ~~Hub dry_run 回填 PERFORMANCE_REPORT~~ ✅ project-hub-2 · `:18086` 20/36 ms · `:8082` 86/212 ms | D4 **8** |
| 3 | ~~**P1**~~ | ~~API Key 首期~~ ✅ | D5 8 |
| 4 | ~~**P1**~~ | ~~核心单测 21 cases~~ ✅ | D6 8 |
| 5 | ~~**P2**~~ | ~~ChunkPreview UI~~ ✅ Round-6 | D8 **8** |
| 6 | ~~**P2**~~ | ~~检索片段高亮~~ ✅ Round-7 | D8 **9** |
| 7 | **P2** | OpenAPI 导出 | D7 巩固 |
| 8 | **P3** | JDK 17 升级 RFC | D10 7→8 |

---

## 与 ai-portfolio 矩阵对照

| 检查项 | Round-6 |
| --- | --- |
| README / Docker / CI / 压测 / 安全 / 部署 / 演示 | ✓ |
| 多租户 | N/A (by design) |
| Hub / 矩阵 | ✓（18086 verify 文档） |
| GAP-MATRIX 均分 | **8.4**（Round-7 已同步） |

---

## 相关文档

- [README.md](../README.md)
- [docs/DEMO.md](./DEMO.md)
- [PERFORMANCE_REPORT.md](../PERFORMANCE_REPORT.md)
- [SECURITY.md](../SECURITY.md)
- [DEPLOYMENT.md](../DEPLOYMENT.md)

*Round-7 复评 **8.4** · project-hub-1 CI smoke-mock + 检索片段高亮 · 下一目标：OpenAPI / JDK17 RFC → **8.5+**。*
