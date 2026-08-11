# 部署、备份与恢复

## 支持的本地拓扑

| Compose 文件 | 向量后端 | 宿主端口 | 用途 |
|---|---|---|---|
| `docker-compose.yml` | InMemory | 19050 | 短期开发；应用重启后需从 MySQL 重建向量 |
| `docker-compose-chroma.yml` | Chroma 0.4.24 | 19050 | 推荐主路径；复用 shared-infra 持久化集合 |
| `docker-compose-milvus.yml` | Milvus 2.5.27 | 19050、19055-19056 | 大数据量单机方案；复用 shared-infra MinIO |

三种拓扑都只把项目端口发布到 `127.0.0.1`，没有固定容器名。MySQL、Redis、Chroma 和 MinIO 由同级 `shared-infra` 统一提供，本项目不会重复创建。共享宿主端口为 MySQL `13306`、Redis `16379`、Chroma `18000`；其既有端口不占用本项目 `19050-19059` 配额。

## 推荐主路径

```bash
cp .env.example .env
docker compose --project-directory ../shared-infra -f ../shared-infra/docker-compose.yml --profile study up -d --wait mysql redis chroma
docker compose -p ragsh -f docker-compose-chroma.yml up -d --build
node scripts/smoke-mock-demo.mjs http://127.0.0.1:19050
```

查看状态：

```bash
docker compose -p ragsh -f docker-compose-chroma.yml ps
docker compose -p ragsh -f docker-compose-chroma.yml logs -f app
curl http://127.0.0.1:19050/api/health
curl http://127.0.0.1:19050/api/readiness
```

`/api/health` 只说明进程可响应；只有 `/api/readiness` 返回 HTTP 200 且 `obj.status=UP` 时，MySQL、Redis 和向量索引才一致可用。

## 资源与文件系统

- 应用容器以 UID 10001 运行，根文件系统只读，只有 `data/inbox` 卷和 `/tmp` tmpfs 可写。
- Compose 配置了 CPU、内存、健康检查和停止宽限期；shared-infra 必须先达到健康状态。
- 本项目 `docker compose down -v` 只删除 app inbox，以及 Milvus 方案自身的 etcd/Milvus 卷；不会删除共享 MySQL、Redis、Chroma 或 MinIO 数据。
- 默认镜像和模型配置见 `.env.example`；真实密钥只放未提交的 `.env` 或外部密钥系统。

## 真实模型

设置 `APP_RAG_PROVIDER=openai` 时必须同时提供 Chat 和 Embedding Key。Rerank Key 可单独配置。启动后先检查：

```bash
curl http://127.0.0.1:19050/api/health
# ragProvider 应为 openai，而不是 mock
```

Embedding 模型/维度变化属于索引迁移。先备份，再使用 `POST /api/vector/rebuild`，不要让旧集合与新维度混用。

## 远程访问

默认配置只适合本机。允许远程访问必须同时满足：

```dotenv
APP_PUBLIC_ACCESS_ENABLED=true
APP_API_KEY_ENABLED=true
APP_API_KEY=<long-random-secret>
```

并在可信反向代理终止 TLS、限制来源和请求体。应用会拒绝“公开绑定但未配置 API Key”的启动组合。

## 一致备份

下面以推荐 Chroma 路径为例。先停止应用写入。共享 MySQL 只导出 `rag_study_helper`；Redis DB 3 和 Chroma 集合属于本项目，但底层 RDB/目录是共享介质，必须由 shared-infra 做一致快照，不能从本仓库删除或覆盖共享卷。备份目录必须位于受控存储，不要提交 Git。

```bash
mkdir -p backup/ragsh
docker compose -p ragsh -f docker-compose-chroma.yml stop app

node scripts/chroma-collection-backup.mjs backup \
  rag_study_helper_v2 \
  backup/ragsh/chroma-collection.json \
  --base-url http://127.0.0.1:18000

docker exec infra-mysql sh -lc \
  'MYSQL_PWD="rag-local-app-password" mysqldump -urag --single-transaction --routines --triggers --hex-blob --databases rag_study_helper --result-file=/tmp/rag-study-helper.sql'
docker cp infra-mysql:/tmp/rag-study-helper.sql backup/ragsh/mysql.sql

docker exec infra-redis redis-cli SAVE
docker cp infra-redis:/data/dump.rdb backup/ragsh/shared-redis.rdb

docker stop infra-chroma
docker cp infra-chroma:/chroma/chroma backup/ragsh/shared-chroma
docker start infra-chroma
docker compose -p ragsh -f docker-compose-chroma.yml start app
```

集合导出脚本会保存 embedding、metadata、文档正文、行数和内容 SHA256；文件包含敏感资料。集合名不是默认值时，应把 `rag_study_helper_v2` 替换为 `.env` 中的 `APP_VECTOR_COLLECTION`。脚本失败会非零退出。

停止共享 Chroma 前必须确认没有其他应用正在写入；无法取得维护窗口时，可保留已经校验的集合导出，或用 MySQL 中的活跃分块安全重建本项目集合。随后对 `mysql.sql`、`shared-redis.rdb`、`chroma-collection.json` 和 `shared-chroma/**` 计算 SHA256，并保存哈希与时间。Redis 快照含其他逻辑库，必须按共享基础设施备份管理；本项目恢复只能提取 DB 3，不能覆盖正在运行的共享 Redis。会话默认 TTL 为 1 小时。

## 恢复演练

恢复是破坏性操作。先在独立 Compose 项目和空卷演练，不要直接覆盖唯一副本。

1. 在临时数据库名中导入 `mysql.sql`，不得覆盖共享生产库。
2. 在隔离 Redis 中读取共享 RDB，只迁移 DB 3 的键到验收用逻辑库，禁止执行共享实例 `FLUSHALL`。
3. 把 Chroma 快照复制到隔离验收目录，或从临时 MySQL 的活跃分块安全重建唯一验收集合。
4. 用被忽略的 `.local` 覆盖文件把验收应用指向临时数据库、Redis DB 和集合。
5. 启动应用，确认 `/api/readiness` 中 `activeChunks == indexedChunks`，再抽查文档、会话、检索和引用。

集合级恢复必须写入一个不存在的新集合；脚本会在导入后重新计算行数和 SHA256，校验失败时尝试删除不完整的新集合：

```bash
node scripts/chroma-collection-backup.mjs restore \
  backup/ragsh/chroma-collection.json \
  rag_study_helper_restore_drill \
  --base-url http://127.0.0.1:18000
```

本仓库的恢复验收脚本要求备份中包含固定评估空间：

```bash
python scripts/restore_acceptance.py \
  --base-url http://127.0.0.1:19058 \
  --expected-documents 5 \
  --expected-vectors 13
```

脚本会验证 MySQL 空间和文档、Chroma 原向量检索、SSE 精确引用以及 Redis 会话写入/回读，并清理临时会话。生产备份应按自己的已知计数和抽样问题调整期望值。

## 多实例

多个应用实例必须共享同一 MySQL、Redis 和向量集合，且使用相同 Embedding 维度。Redis 保存会话、分布式锁和 `OVERALL` 限流桶；MySQL 是文档状态真源。

本地双实例验收：

```bash
python scripts/multi_instance_acceptance.py \
  --primary-url http://127.0.0.1:19050 \
  --secondary-url http://127.0.0.1:19057 \
  --secondary-key <test-key> \
  --rate-limit 4
```

该脚本要求两个实例使用相同的低容量测试限流配置，不应用于生产流量。

## 升级与回退

1. 备份 MySQL、Redis 和向量数据。
2. 在副本环境运行 Flyway、`/api/readiness`、固定评估和浏览器 smoke。
3. 部署新镜像；Flyway 只向前迁移，不自动降级表结构。
4. 回退应用前确认旧版本理解当前 schema。否则恢复升级前完整快照。

当前迁移和回退原则见 [ADR-0001](docs/adr/0001-v2-runtime-and-reliability-architecture.md)。

## 排障

| 现象 | 判断和处理 |
|---|---|
| `/api/readiness` 503 | 查看 `obj.components` 和 app 日志；不要只重启应用掩盖依赖问题 |
| `activeChunks != indexedChunks` | 运行 `/api/vector/reconcile`；仍不一致时在备份后重建 |
| 任务长期 `PROCESSING` | 恢复器会接管超时任务；检查 Redis 锁、磁盘和失败原因 |
| 飞书运行显示删除被保护 | 检查分页/权限/429/超时和删除阈值，不要绕过安全门直接批量删库 |
| HTTP 429 | 遵守 `Retry-After`；确认多实例共享 Redis 且限流配置一致 |
| Windows 构建 JAR 失败 | 先停止正在运行的同一路径 JAR；Windows 会锁定可执行 JAR |

## 验收入口

```bash
docker compose -f docker-compose.yml config --quiet
docker compose -f docker-compose-chroma.yml config --quiet
docker compose -f docker-compose-milvus.yml config --quiet
docker compose --project-directory ../shared-infra -f ../shared-infra/docker-compose.yml --profile study up -d --wait mysql redis chroma
node scripts/smoke-mock-demo.mjs http://127.0.0.1:19050
python loadtest/dry_run.py -n 30 -c 4
```

测试和质量门详见 [docs/DIMENSION-AUDIT.md](docs/DIMENSION-AUDIT.md)。
