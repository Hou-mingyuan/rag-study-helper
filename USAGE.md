# RAG Study Helper 使用指南

面向日常部署与问数操作的简明步骤。架构与 API 细节见 [README.md](README.md)。

## 1. 准备环境

- Docker Desktop（推荐），或 JDK 8+ + 本地 MySQL 8 + Redis 7
- **Mock 零密钥演示**（推荐作品集）：`APP_RAG_PROVIDER=mock`，Key 可留空 — 见 [docs/DEMO.md](docs/DEMO.md)
- **真实 LLM**：Chat + Embedding 两套 API Key

```bash
cp .env.example .env
# Mock：默认即可；真实 LLM：填入 APP_RAG_CHAT_API_KEY、APP_RAG_EMBEDDING_API_KEY
```

> 切勿提交含真实密钥的 `.env` 文件。

## 2. 启动（Docker 推荐）

```bash
# 开发/零依赖（向量存内存，重启丢失）
docker compose up -d

# 持久化向量（二选一）
docker compose -f docker-compose-chroma.yml up -d
docker compose -f docker-compose-milvus.yml up -d
```

浏览器访问：**http://localhost:8080**

查看日志：

```bash
docker compose logs -f app
```

如果本机 3306、6379 或 8080 已被占用，可在 `.env` 中改宿主机端口：

```bash
MYSQL_HOST_PORT=13306
REDIS_HOST_PORT=16379
APP_HOST_PORT=18080
```

## 3. 导入知识库

| 方式 | 操作 |
| --- | --- |
| Web 上传 | 页面「知识库」面板拖拽或选择 PDF/Word/Excel/Markdown 等 |
| 目录扫描 | 将文件放入 `data/docs/`，点击「扫描目录」 |
| 飞书同步 | 在 `.env` 配置 `APP_FEISHU_APP_ID/SECRET/SPACE_ID` 并设 `APP_FEISHU_SYNC_ENABLED=true` |

## 4. 开始问答

1. 在聊天框输入自然语言问题（如「各产品类目的销售额占比」）
2. 回答以 SSE 流式输出，并附带引用片段
3. 多轮追问会自动带上会话上下文（Redis 存储，默认 TTL 过期）

## 5. 常用 API（curl）

```bash
# 健康检查
curl http://localhost:8080/api/health

# 流式问答
curl -N -X POST http://localhost:8080/api/chat \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"demo-1","question":"文档里提到了哪些核心概念？"}'

# 上传文档
curl -X POST http://localhost:8080/api/documents/upload \
  -F "file=@./sample.pdf"

# 列出已入库文档
curl http://localhost:8080/api/documents
```

## 6. 本地 Maven 运行

```bash
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS rag_study_helper;"
mysql -u root -p rag_study_helper < init.sql

export APP_RAG_CHAT_API_KEY=sk-xxx
export APP_RAG_EMBEDDING_API_KEY=sk-xxx
export SPRING_DATASOURCE_PASSWORD=your-mysql-password

mvn spring-boot:run
```

## 7. 切换向量库

编辑 `application.yml` 或环境变量：

```yaml
vector:
  store:
    type: in-memory   # chroma | milvus
```

切换 Embedding 模型后，请同步修改 `milvus.dimension`（如 OpenAI `text-embedding-3-small` 为 1536）。

## 8. 常见问题

| 现象 | 处理 |
| --- | --- |
| 启动报 LLM 未配置 | 检查 `.env` 中 `APP_RAG_CHAT_API_KEY` / `APP_RAG_EMBEDDING_API_KEY` |
| 429 限流 | 调大 `APP_RATE_LIMIT_IP_RATE` 或等待令牌桶恢复 |
| Milvus 连接失败 | 首次启动需 1–2 分钟，`docker compose ps` 确认 healthy |
| 飞书同步无内容 | 确认应用已发布、权限齐全，且已加入目标知识库 |

## 9. 运行测试

```bash
mvn test
mvn package -DskipTests
```
