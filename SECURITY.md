# 安全边界

## 产品边界

当前版本是本地单用户工具。知识空间提供数据隔离，API Key 提供最小访问控制，但它们不是组织级身份、角色或多租户系统。需要团队公开服务时，应在可信网关后增加 TLS、SSO、审计和网络策略。

## 默认网络策略

- 原生应用默认监听 `127.0.0.1:8080`；本项目验收时显式设置 `SERVER_PORT=19050`。
- Compose 容器内监听原生端口 `0.0.0.0:8080`，宿主只发布 `127.0.0.1:19050`，并设置 `APP_HOST_PUBLISHED_LOOPBACK=true`。
- 非回环监听必须显式声明 `APP_PUBLIC_ACCESS_ENABLED=true`；公开访问还必须启用并正确配置 API Key，否则启动失败。
- MySQL、Redis 和 Chroma 复用 `shared-infra`，只发布到宿主回环地址并按数据库、Redis DB 3 和集合隔离；本项目不重复启动这些容器。

## 密钥

| 变量 | 内容 |
|---|---|
| `APP_API_KEY` | 应用 API Key |
| `APP_RAG_CHAT_API_KEY` | Chat 服务 Key |
| `APP_RAG_EMBEDDING_API_KEY` | Embedding 服务 Key |
| `APP_RAG_RERANK_API_KEY` | Rerank 服务 Key |
| `FEISHU_APP_SECRET` | 飞书应用 Secret |
| `MYSQL_PASSWORD` / `REDIS_PASSWORD` | 数据服务口令；本地共享 Redis 默认无密码 |

真实值只放未提交的 `.env`、进程环境或密钥系统。日志不记录 Key、飞书完整响应、文档正文、问题正文或检索分块正文。

API Key 使用常量时间比较。开启后，除静态资源、`/api/health` 和 `/api/readiness` 外，所有 `/api/**` 请求必须提供 `X-API-Key`。Web 页面只把 Key 保存在当前标签页 `sessionStorage`。

## HTTP 与浏览器

应用统一返回：

- `Content-Security-Policy`，禁止第三方脚本/样式、对象、base 注入和 framing。
- 可变 `/api/**` 响应使用 `Cache-Control: no-store`，防止浏览器复用删除前的空间或任务状态。
- `X-Content-Type-Options: nosniff`。
- `X-Frame-Options: DENY`。
- `Referrer-Policy: no-referrer`。
- `Permissions-Policy` 和 `Cross-Origin-Opener-Policy`。
- `X-Request-ID`，用于日志关联。

前端不把模型或文档内容拼接进 `innerHTML`，所有动态内容使用 DOM 文本节点。引用只按服务端返回的文档/分块 ID 定位。

## 上传与解析

- 文件名会规范化并阻止路径逃逸。
- 后缀白名单和前后端大小限制一致，默认单文件/请求 50MB。
- 抽取正文还有最大字符数限制，避免解压/解析放大。
- 入库文件先进入私有 inbox，再由任务执行；API 不返回服务器绝对路径。
- Office 宏不会执行，但不可信文件仍应在网关做恶意文件扫描和内容策略检查。

## RAG 与资料信任

- 资料被明确标记为不可信上下文，Prompt 指示模型忽略资料内的指令。
- 找不到依据时必须拒答，不允许偷偷使用模型常识伪装为资料答案。
- 回答引用必须落在本次检索候选的 `documentId/chunkId` 中。
- 查询会再次用 MySQL 校验空间、文档版本和分块 `READY` 状态，过期向量不可用。

这些措施降低风险，但不能替代真实模型场景下的提示注入评估和人工抽查。

## 飞书防误删

本地删除只在以下条件全部成立时执行：

1. 远端空间、分页和递归枚举完整成功。
2. 同一远端节点连续缺失达到配置次数。
3. 删除绝对数量和比例不超过阈值。
4. 零远端结果保护未触发。
5. 当前实例持有分布式同步锁，且目标知识空间匹配。

429、5xx、超时、游标循环、页数上限、权限收缩或部分结果都会使本轮删除进入保护状态。运行报告保留计数和原因，不保留凭据或正文。

## 限流与依赖故障

聊天使用 Redis/Redisson 的共享 IP 令牌桶和全局日计数。仅在明确配置可信代理时读取 `X-Forwarded-For`。命中限流返回 HTTP 429 和 `Retry-After`。

Redis 故障时返回 HTTP 503，**不会**静默放行无限请求。会话写入失败也不会把回答伪装成完整成功。

## 数据保留

| 数据 | 存储与保留 |
|---|---|
| 空间、文档、任务、同步报告、会话元数据 | MySQL，直到显式删除 |
| 会话正文 | Redis List，默认 TTL 1 小时、最多 20 条消息 |
| 向量 | 选定向量后端；删除/更新通过补偿和对账收敛 |
| 临时上传 | inbox；任务终态后清理 |

备份文件包含敏感资料，应加密、限制访问并按保留策略删除。步骤见 [DEPLOYMENT.md](DEPLOYMENT.md)。

## 发布前检查

```bash
git diff --check
git status --short
docker compose -f docker-compose-chroma.yml config --quiet
```

CI 使用 Trivy 扫描源码依赖和秘密，并在构建后单独扫描最终应用镜像的 OS 包；两层 HIGH/CRITICAL 门禁都不忽略无修复条目。

2026-07-23 最终候选证据：

- 生产 CycloneDX SBOM 共 160 个组件，Java HIGH/CRITICAL 为 0。
- 最终镜像为 Alpine 3.23.5，共 73 个 OS 包，HIGH/CRITICAL 为 0。
- 镜像内 9 个 Netty 模块统一为 `4.1.136.Final`，不再包含受 `CVE-2026-59901` 影响的 4.1.135。
- 源码漏洞、配置错误和 secret 扫描均为 0。

发布前仍应核对基础镜像摘要及真实部署环境配置。

## 漏洞反馈

请通过维护者的私有渠道报告漏洞。不要在公开 Issue 中粘贴 Key、访问令牌、数据库备份或客户资料样本。
