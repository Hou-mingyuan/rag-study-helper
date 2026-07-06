# RAG Study Helper · 安全说明

## 密钥与敏感配置

| 项 | 说明 |
| --- | --- |
| `APP_RAG_CHAT_API_KEY` | Chat 模型密钥，**仅环境变量 / `.env`** |
| `APP_RAG_EMBEDDING_API_KEY` | Embedding 密钥 |
| `APP_RAG_RERANK_API_KEY` | Rerank 密钥（可复用 Embedding） |
| `APP_FEISHU_APP_SECRET` | 飞书应用密钥，可选 |
| `MYSQL_ROOT_PASSWORD` | MySQL root，Docker 默认 `root` |
| `SPRING_REDIS_PASSWORD` | Redis 密码，生产建议启用 |

**禁止**将 `.env` 或真实 Key 提交 Git（已在 `.gitignore`）。

## 访问控制

- 当前版本 **无应用层登录**；`/api/chat` 与文档上传对可达网络开放。
- 生产必须在网关 / VPN / IP 白名单后部署，或前置 SSO 反向代理。
- 上传接口可接收多种办公文档，需限制上传大小与 MIME（Spring `multipart`：**单文件 / 单次请求上限 50MB**，见 `application.yml`）。

## 限流与成本

- `@RateLimit`：IP 令牌桶 + 全局日配额（Redisson + Redis）。
- Redis 不可用时限流自动降级（**不限流**）——生产必须保证 Redis 可用。
- 调整 `APP_RATE_LIMIT_IP_RATE`、`APP_RATE_LIMIT_DAILY_MAX` 防止 LLM/Embedding 费用失控。

## 数据安全

| 数据 | 存储 | 注意 |
| --- | --- | --- |
| 文档原文/分块 | 向量库 + MySQL | 备份与访问权限按生产策略 |
| 会话 | Redis TTL | 多实例共享，避免敏感内容长期保留 |
| 飞书同步 | 拉取 Wiki 内容 | 最小权限：`wiki:wiki:readonly` 等 |

## 依赖与上传面

- 解析 PDF/Office/HTML：注意恶意文件（宏、超大文件）— 建议网关限制 body 大小。
- 向量库 InMemory 模式**不持久化**，仅开发用；生产用 Chroma/Milvus 并隔离网络。

## 生产检查清单

- [ ] 修改 MySQL/Redis 默认口令
- [ ] 独立 Chat/Embedding Key，设置配额告警
- [ ] 启用 HTTPS 与网关鉴权
- [ ] 使用 `docker-compose-chroma.yml` 或 Milvus，禁用 InMemory
- [ ] 确认飞书同步权限最小化
- [ ] 监控 `/api/health` 与 429 比例

## 漏洞反馈

请通过私有渠道联系维护者，勿在公开 Issue 粘贴 Key 或客户文档样本。
