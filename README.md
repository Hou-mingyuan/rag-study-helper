# RAG Study Helper

RAG Study Helper 是一个本地优先、可评估、可追溯的学习资料问答工作台。当前版本为 `2.0.0-RC1`，默认使用完全离线的 Mock 模型，启动后即可完成资料导入、分块检查、检索问答和引用定位。

## 当前能力

- 知识空间：空间级文档、分块、会话、任务和飞书同步隔离。
- 资料导入：Web 上传、容器内目录扫描；支持 `txt/md/csv/json/xml/pdf/xls/xlsx/docx/pptx/html`。
- 可靠入库：SHA256 去重、确定性向量 ID、版本状态机、幂等键、取消、重试和失败补偿。
- 向量后端：InMemory、Chroma 0.4.24、Milvus 2.5.27 使用同一项目契约。
- RAG 链路：会话感知查询改写、向量召回、可选 Rerank、资料边界 Prompt、流式回答和明确拒答。
- 精确引用：回答引用包含 `documentId/chunkId`，可定位到文件、分块、页码/章节和字符区间。
- 飞书同步：分页、429/5xx/超时重试、更新时间增量、分布式锁、运行报告和删除保护。
- Web 工作台：任务进度、分块预览、会话历史、流式取消/重试、离线/错误/空状态、暗黑模式和移动端布局。
- 运维：健康/就绪、结构化日志、请求 ID、Redis 全局限流、API Key、向量对账/重建和 Flyway 迁移。

## 技术基线

| 层 | 版本/实现 |
|---|---|
| 运行时 | Java 17，Spring Boot 3.5.16 |
| RAG | LangChain4j 1.18.0；Chroma/Milvus 适配器 1.18.0-beta28 |
| 元数据 | MySQL 8.4，MyBatis-Plus 3.5.17，Flyway 11.20.3 |
| 会话/协调 | Redis 7，Redisson 3.52.0 |
| 文档 | PDFBox、Apache POI、JSoup |
| Web | 原生 HTML/CSS/ES modules，无外部运行时 CDN |

升级取舍和迁移约束见 [ADR-0001](docs/adr/0001-v2-runtime-and-reliability-architecture.md)。主分支不保留 Java 8/Boot 2 旁路。

## 三分钟启动

前置条件：Windows 11、Linux 或 macOS；Docker Engine 24+、Docker Compose v2、Node.js 22，以及同级目录中的 `shared-infra`。原生测试另需 JDK 17、Maven 3.9 和 Python 3.12。建议至少 4 核、8 GB 可用内存；Milvus 路径建议 12 GB。首次拉取镜像和构建可能超过三分钟。

### Windows

```powershell
./scripts/demo-mock.ps1
```

### Linux/macOS

```bash
./scripts/demo-mock.sh
```

脚本执行以下严格流程：

1. 从 `.env.example` 创建未提交的 `.env`（仅当文件不存在）。
2. 启动或复用 `shared-infra` 的 MySQL、Redis、持久化 Chroma，再构建应用；不会创建项目专属的同类容器。
3. 等待 `/api/readiness`。
4. 扫描内置演示资料并等待异步入库完成。
5. 完成一次真实检索、SSE、精确引用和 Redis 会话回读。

成功后打开 [http://127.0.0.1:19050](http://127.0.0.1:19050)。页面会显著显示 `MOCK 评估模式`，不会访问外部模型服务。

手工启动等价命令：

```bash
cp .env.example .env
docker compose --project-directory ../shared-infra -f ../shared-infra/docker-compose.yml --profile study up -d --wait mysql redis chroma
docker compose -f docker-compose-chroma.yml up -d --build --wait
node scripts/smoke-mock-demo.mjs http://127.0.0.1:19050
```

停止服务但保留数据：

```bash
docker compose -f docker-compose-chroma.yml down
```

本仓库的 `down -v` 只删除应用 inbox 卷，不会删除 shared-infra 数据。共享 MySQL、Redis 或 Chroma 的清理必须按空间、数据库和集合定向执行，不能用本项目命令删除共享卷。

## 端口

本项目发布的宿主端口只绑定回环地址并限制在 `19050-19059`。共享基础设施由 `shared-infra` 统一管理，其既有宿主端口不属于本项目端口配额。

| 端口 | 服务 |
|---:|---|
| 19050 | Web/API |
| 19055 | Milvus gRPC（Milvus 方案） |
| 19056 | Milvus 健康端点（Milvus 方案） |
| 19057-19059 | 本地验收临时实例 |

共享服务仅绑定宿主回环地址：MySQL `13306`、Redis `16379`、Chroma `18000`。应用容器使用原生端口 `8080`，依赖容器分别保持 `3306/6379/8000`。

## 首次使用

1. 在左侧新建或选择知识空间。
2. 打开“资料与分块”，上传文件；也可点击“扫描内置目录”。
3. 在“任务与同步”观察排队、解析、向量写入和完成状态；失败任务可重试，运行中任务可取消。
4. 点击文档检查分页分块和完整正文。
5. 回到“带引用问答”提问；点击引用可定位实际分块。
6. Mock 找不到资料依据时会精确返回“根据当前知识空间的资料，没有找到可支持该问题的信息。”，不会退化为普通聊天。

完整交互和 API 示例见 [USAGE.md](USAGE.md)。

## 模型模式

### Mock（默认）

`APP_RAG_PROVIDER=mock` 使用本地确定性 Embedding、Rerank 和流式回答，适合零密钥演示、CI 和固定评估，不代表真实大模型质量。

### OpenAI 兼容服务

在未提交的 `.env` 中设置：

```dotenv
APP_RAG_PROVIDER=openai
APP_RAG_CHAT_API_KEY=...
APP_RAG_CHAT_BASE_URL=https://api.example.com/v1
APP_RAG_CHAT_MODEL_NAME=...
APP_RAG_EMBEDDING_API_KEY=...
APP_RAG_EMBEDDING_BASE_URL=https://api.example.com/v1
APP_RAG_EMBEDDING_MODEL_NAME=...
APP_RAG_RERANK_API_KEY=...
APP_RAG_RERANK_BASE_URL=https://api.example.com/v1
APP_RAG_RERANK_MODEL_NAME=...
```

Embedding 维度或模型变化后，不得直接复用旧集合。先备份，再使用界面“重建向量索引”或 `POST /api/vector/rebuild`；启动时会拒绝已知维度不一致的集合。

## 数据一致性

MySQL 是活跃文档版本、任务和分块状态的真源。向量写入采用确定性 ID；失败会记录补偿项并由对账任务恢复。查询只接受 MySQL 中仍为 `READY` 的分块，因此过期、已删除或跨空间向量不能成为回答依据。

飞书删除必须同时满足：远端枚举完整成功、同一节点连续缺失达到阈值、删除数量和比例不越界、分布式锁持有且目标知识空间匹配。任一分页、递归、429、超时或权限错误都会禁止本轮删除。

推荐 Chroma 路径可在停止应用写入后按集合导出，并同时记录行数与 SHA256：

```bash
node scripts/chroma-collection-backup.mjs backup rag_study_helper_v2 backup/ragsh/chroma-collection.json --base-url http://127.0.0.1:18000
```

完整的 MySQL、Redis、Chroma 备份和隔离恢复步骤见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## API 概览

| 路径 | 用途 |
|---|---|
| `GET /api/health` / `GET /api/readiness` | 进程与依赖状态 |
| `/api/spaces` | 知识空间 CRUD |
| `/api/spaces/{id}/documents` | 列表、上传、扫描、删除、分块定位 |
| `/api/spaces/{id}/jobs` | 入库任务列表、取消、重试 |
| `/api/spaces/{id}/sessions` | 会话列表、详情、改名、删除 |
| `POST /api/spaces/{id}/chat` | SSE 问答 |
| `/api/spaces/{id}/feishu` | 飞书状态、运行报告和手工同步 |
| `/api/vector/status|reconcile|rebuild` | 向量状态、对账和安全重建 |

响应使用标准 HTTP 状态，同时保留 `{resCode,msg,obj}` 业务体。每个响应返回 `X-Request-ID`。

## 验证

基础单测：

```bash
mvn -gs .mvn/settings-central.xml -s .mvn/settings-central.xml -B test
node --check src/main/resources/static/app.js
node --test src/test/js/ui-core.test.mjs
```

运行中的 Mock/Chroma 栈可执行：

```bash
node scripts/evaluate-mock.mjs --base-url http://127.0.0.1:19050 --out target/acceptance/evaluation.json
python loadtest/dry_run.py -n 30 -c 4 --output target/acceptance/performance.json
python scripts/browser_acceptance.py
```

固定评估集包含 5 份资料和 27 个问题，覆盖精确术语、追问、无答案、中文长文和跨文档检索。测试矩阵与最新门槛见 [docs/DIMENSION-AUDIT.md](docs/DIMENSION-AUDIT.md)。

## 文档

- [使用与 API](USAGE.md)
- [部署、备份和恢复](DEPLOYMENT.md)
- [安全边界](SECURITY.md)
- [性能基线](PERFORMANCE_REPORT.md)
- [Mock 演示](docs/DEMO.md)
- [架构决策](docs/adr/0001-v2-runtime-and-reliability-architecture.md)
- [变更记录](CHANGELOG.md)

## 明确边界

- 当前产品边界是本地单用户；API Key 是最小远程访问边界，不是完整多租户身份系统。
- InMemory 仅用于开发；推荐 Chroma。Milvus Compose 是单机持久化方案，不宣称集群高可用。
- Mock 指标只用于可重复回归；接入真实模型后应使用同一资料集重新评估质量、成本和延迟。

## 许可证

本项目使用 [MIT License](LICENSE)。
