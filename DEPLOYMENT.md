# RAG Study Helper · 部署说明

日常操作见 [USAGE.md](USAGE.md)。本文档补充 **Project Hub Docker Profile** 与生产要点。

## 部署方式对比

| 方式 | 命令 | 应用端口 |
| --- | --- | --- |
| 独立 Compose（InMemory） | `docker compose up -d` | `${APP_HOST_PORT:-8080}` |
| 独立 Compose（Chroma） | `docker compose -f docker-compose-chroma.yml up -d` | 同上 |
| **Hub Profile** | 见下文 | **18086**（默认） |

## Project Hub Docker Profile（18086）

在 monorepo 内通过 `ai-portfolio/docker` 统一编排，避免与 ChatBI(8080)、Lumiere 等冲突：

```powershell
cd d:\project-hub\ai-portfolio\docker
copy .env.example .env
# 填入 APP_RAG_* Key；Profile 端口默认 RSH_APP_PORT=18086
docker compose -f docker-compose.profiles.yml --profile rag-study-helper up -d --build
```

| 服务 | Hub 默认宿主机端口 |
| --- | --- |
| 应用 | **18086** |
| Chroma | 18087 |
| MySQL | 13308 |
| Redis | 16379 |

验证：

```bash
curl http://localhost:18086/api/health
# 浏览器 http://localhost:18086
```

详见 [ai-portfolio/docker/DOCKER-DESKTOP.md](../ai-portfolio/docker/DOCKER-DESKTOP.md) 与 [verify-rag-study-helper.md](../ai-portfolio/docker/verify-rag-study-helper.md)。

## 独立仓库 Compose

```bash
cd rag-study-helper
cp .env.example .env
docker compose up -d --build
curl http://localhost:8080/api/health
```

端口冲突时在 `.env` 设置：

```bash
APP_HOST_PORT=18086
MYSQL_HOST_PORT=13308
REDIS_HOST_PORT=16379
```

## 演示账号与默认口令（Docker）

> **仅供本地演示**，生产必须修改。

| 组件 | 用户 | 密码 / 库 |
| --- | --- | --- |
| MySQL | `root` | `${MYSQL_ROOT_PASSWORD:-root}` |
| 数据库名 | — | `rag_study_helper` |
| Redis | 无用户 | 默认无密码 |
| 应用鉴权 | — | **无**（见 SECURITY.md） |

API Key 非「账号」，需在 `.env` 配置：

- `APP_RAG_CHAT_API_KEY`
- `APP_RAG_EMBEDDING_API_KEY`

## 核心流程（四步）

```
配置 .env → 启动栈 → 导入知识库 → SSE 问答
```

1. **配置**：复制 `.env.example`，填入 Chat + Embedding Key。
2. **启动**：Compose 或 Hub Profile `18086`；等待 MySQL/Redis healthy。
3. **入库**：Web 上传 / 扫描 `data/docs/` / 飞书同步（可选）。
4. **问答**：`POST /api/chat` 或 Web UI 流式对话；观察引用与限流 429。

## 健康检查与日志

```bash
docker compose ps
docker compose logs -f app
curl -sf http://localhost:8080/api/health
curl -sf http://localhost:8080/api/documents
```

## 向量库选型

| 文件 | 场景 |
| --- | --- |
| `docker-compose.yml` | 开发，InMemory |
| `docker-compose-chroma.yml` | 中型，持久化 |
| `docker-compose-milvus.yml` | 生产，分布式 |

Hub Profile 使用 **Chroma**（端口 18087）。

## 升级

```bash
git pull
docker compose up -d --build
```

MySQL 卷 `mysql-data` 含元数据；升级前备份。Embedding 维度变更需重建向量集合（见 README）。

## 压测

见 [PERFORMANCE_REPORT.md](PERFORMANCE_REPORT.md)（health/documents smoke，**不默认压 /api/chat** 以免消耗 LLM）。

快速验收：

```bash
python loadtest/dry_run.py --base-url http://localhost:8080
# Hub Profile
python loadtest/dry_run.py --base-url http://localhost:18086
```

## 相关文档

- [SECURITY.md](SECURITY.md)
- [README.md](README.md)
- [USAGE.md](USAGE.md)
