# 使用与 API

推荐先执行 `scripts/demo-mock.ps1` 或 `scripts/demo-mock.sh`，再访问 `http://127.0.0.1:19050`。以下示例均以默认 Mock/Chroma 栈为准。

## 页面工作流

### 知识空间

- “＋ 新建”创建空间；名称最长 100 字，说明最长 500 字。
- 文档、任务、会话和查询严格带 `spaceId`。
- 删除非空空间前页面会确认；后端拒绝删除仍有文档或运行任务的空间。

### 资料与分块

- 上传支持 `txt/md/csv/json/xml/pdf/xls/xlsx/docx/pptx/html`，默认最大 50MB。
- 相同 SHA256 内容重复上传会返回已有任务/文档，不重复写向量。
- 同名但内容变化会产生新版本；旧版本在新版本完整激活前仍可查询。
- “扫描内置目录”读取容器内 `data/docs`；每个文件成为独立可恢复任务。
- 文档列表可检查分块，分块抽屉支持分页、完整正文和字符/页码/章节位置。

### 任务与同步

- 状态包括 `QUEUED/PROCESSING/COMPLETED/FAILED/CANCELLED`。
- 页面显示阶段、进度和失败原因；排队/运行任务可取消，失败/取消任务可重试。
- 删除文档也通过任务执行，并使用确定性向量 ID 和补偿记录保证最终一致。
- 飞书启用后显示同步状态与运行报告；删除被保护时报告会说明原因。

### 带引用问答

- 每次发送绑定当前知识空间和会话。
- SSE 依次发送 `status -> retrieval -> token* -> done`；失败和取消只有一个终态。
- “停止生成”会中断当前请求并提供“重新生成”。
- 引用按钮按 `documentId/chunkId` 打开原分块，而不是按文件名模糊匹配。
- 会话列表和正文由服务端 MySQL/Redis 管理；浏览器只保存主题、视图和当前标签页 API Key。

## API Key

本机回环地址默认关闭认证。启用后，页面“连接与访问”可在当前标签页保存 Key；关闭标签页即清除。

```dotenv
APP_API_KEY_ENABLED=true
APP_API_KEY=replace-with-a-long-random-value
```

除 `/api/health`、`/api/readiness` 和静态页面外，所有 `/api/**` 请求需携带：

```bash
curl -H "X-API-Key: replace-with-a-long-random-value" \
  http://127.0.0.1:19050/api/spaces
```

启用认证但 Key 为空会导致应用拒绝启动。远程绑定还必须显式设置 `APP_PUBLIC_ACCESS_ENABLED=true`。

## API 示例

### 空间

```bash
curl -X POST http://127.0.0.1:19050/api/spaces \
  -H "Content-Type: application/json" \
  -d '{"name":"数据库课程","description":"教材与课堂笔记"}'

curl http://127.0.0.1:19050/api/spaces
```

后续示例假定空间 ID 为 `2`。

### 上传、扫描和任务

```bash
curl -X POST http://127.0.0.1:19050/api/spaces/2/documents/upload \
  -H "Idempotency-Key: notes-v1" \
  -F "file=@notes.md"

curl -X POST http://127.0.0.1:19050/api/spaces/2/documents/scan
curl http://127.0.0.1:19050/api/spaces/2/jobs
curl -X POST http://127.0.0.1:19050/api/spaces/2/jobs/12/cancel
curl -X POST http://127.0.0.1:19050/api/spaces/2/jobs/12/retry
```

上传和删除返回 HTTP 202。应轮询对应任务，直到进入终态；不要把“已入队”当成“已完成”。

### 文档和精确分块

```bash
curl http://127.0.0.1:19050/api/spaces/2/documents
curl "http://127.0.0.1:19050/api/spaces/2/documents/8/chunks?offset=0&limit=30"
curl http://127.0.0.1:19050/api/spaces/2/documents/8/chunks/21

curl -X DELETE http://127.0.0.1:19050/api/spaces/2/documents/8 \
  -H "Idempotency-Key: delete-document-8"
```

### 会话和 SSE 问答

```bash
curl -X POST http://127.0.0.1:19050/api/spaces/2/sessions \
  -H "Content-Type: application/json" \
  -d '{"title":"第一章复习"}'

curl -N -X POST http://127.0.0.1:19050/api/spaces/2/chat \
  -H "Accept: text/event-stream" \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"SESSION_ID","question":"这份资料的核心结论是什么？"}'

curl http://127.0.0.1:19050/api/spaces/2/sessions/SESSION_ID
```

`retrieval` 事件先返回候选分块和分数；答案正文中的 `[documentId:chunkId]` 必须能在这些候选中落地。

### 飞书

先在 `.env` 配置 `FEISHU_*` 并设置 `FEISHU_SYNC_ENABLED=true`：

```bash
curl http://127.0.0.1:19050/api/spaces/2/feishu/status
curl -X POST http://127.0.0.1:19050/api/spaces/2/feishu/sync
curl http://127.0.0.1:19050/api/spaces/2/feishu/runs
```

`FEISHU_LOCAL_SPACE_ID` 必须等于请求空间。同步失败、枚举不完整或删除阈值越界时，本轮不会删除本地文档。

### 向量维护

```bash
curl http://127.0.0.1:19050/api/vector/status
curl -X POST http://127.0.0.1:19050/api/vector/reconcile
curl -X POST http://127.0.0.1:19050/api/vector/rebuild
```

重建以 MySQL 中 `READY` 分块为真源。运行期间 `/api/readiness` 可能短暂返回 503。

## 状态与错误

- HTTP 400：输入或状态不合法。
- HTTP 401：API Key 缺失或错误。
- HTTP 404：空间、文档、分块、会话或任务不存在。
- HTTP 409：幂等/并发状态冲突。
- HTTP 413/415：文件过大或内容类型不支持。
- HTTP 429：共享限流命中，响应含 `Retry-After`。
- HTTP 503：MySQL、Redis、向量或限流依赖不可用。

所有响应均带 `X-Request-ID`；可在结构化日志中用同一值关联请求。

## 验收工具

```bash
# 严格 Docker smoke；失败非零退出
node scripts/smoke-mock-demo.mjs http://127.0.0.1:19050

# 固定 5 文档、27 问评估
node scripts/evaluate-mock.mjs --base-url http://127.0.0.1:19050 --out target/acceptance/evaluation.json

# 固定资料读写性能门
python loadtest/dry_run.py -n 30 -c 4 --output target/acceptance/performance.json

# 真实 Chromium 页面边界
python scripts/browser_acceptance.py
```

浏览器脚本需要 Python Playwright 1.59 和已安装的 Chromium。更多命令见 [DEPLOYMENT.md](DEPLOYMENT.md)。
