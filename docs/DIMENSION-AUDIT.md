# v2 验收矩阵

本文件记录 `2.0.0-RC1` 的客观门槛，不使用主观总分。报告和截图默认写入忽略目录 `target/`；CI 会上传对应 artifact。

## 功能与可靠性

| 范围 | 验收方式 | 当前证据 |
|---|---|---|
| 知识空间隔离 | 服务单测 + HTTP 集成 | 空间 CRUD、跨空间文档/会话/检索拒绝 |
| 上传/扫描任务 | `IngestionJobServiceTest` + `ApplicationFlowIT` | 进度、取消、重试、并发幂等、路径过滤和失败恢复 |
| 数据一致性 | 故障注入 + Docker 实测 | embedding/向量部分失败补偿、版本原子激活、确定性 ID、对账 |
| 飞书安全 | MockWebServer + 同步服务测试 | 分页、429、超时、失败禁删、连续缺失和数量/比例阈值 |
| RAG | 服务测试 + 固定评估 | 改写、召回、rerank、Prompt、拒答、引用落地和 SSE 单终态 |
| 会话/限流 | Redis 集成 + 双实例脚本 | 原子轮次、多实例共享、429/503 和 `Retry-After` |
| 向量后端 | 同一契约 | InMemory、真实 Chroma、真实 Milvus 各 3/3 |
| 备份恢复 | 隔离端口/空卷恢复 | MySQL/Redis 计数一致，Chroma 13/13，恢复态检索 5 条引用 |

## 自动测试

```bash
mvn -gs .mvn/settings-central.xml -s .mvn/settings-central.xml -B -Pquality \
  -Dapp.it.enabled=true \
  -Dapp.it.mysql.host=127.0.0.1 -Dapp.it.mysql.port=13306 \
  -Dapp.it.mysql.database=rag_study_helper_acceptance_test \
  -Dapp.it.mysql.username=rag -Dapp.it.mysql.password=rag-local-app-password \
  -Dapp.it.redis.host=127.0.0.1 -Dapp.it.redis.port=16379 -Dapp.it.redis.database=3 \
  -Dredis.it.host=127.0.0.1 -Dredis.it.port=16379 -Dredis.it.database=3 \
  -Dit.test=ApplicationFlowIT,RedisCoordinationIT verify
```

2026-07-23 最终结果：118 单元 + 3 集成，0 failures/errors/skips；bundle 行覆盖 80.42%，`service` 81.24%，Feishu client 92.34%，Feishu service 81.31%，vector 85.85%。质量门要求 bundle >=70%，核心包 >=80%。

前端：

```bash
node --check src/main/resources/static/app.js
node --test src/test/js/ui-core.test.mjs
```

结果：6/6。

## 固定评估

资料：5 个 Markdown；问题：27 个，含追问、无答案、精确术语、中文长文和跨文档。

| 指标 | 门槛 | 结果 |
|---|---:|---:|
| 问题数 | >=20 | 27 |
| Hit@5 | >=0.90 | 1.0 |
| MRR | >=0.70 | 0.9792 |
| 引用落地精度 | 1.0 | 1.0 |
| 期望文档引用精度 | >=0.85 | 0.96 |
| 引用覆盖 | >=0.90 | 1.0 |
| 精确术语召回 | >=0.80 | 0.9375 |
| 无答案准确率 | 1.0 | 1.0 |
| 跨文档召回 | >=0.50 | 1.0 |

命令：

```bash
node scripts/evaluate-mock.mjs --base-url http://127.0.0.1:19050 --out target/acceptance/evaluation.json
```

## Web 与性能

- Chromium：`1440x900`、`768x1024`、`375x812`，明暗主题，无横向溢出、遮挡、未处理错误、外部请求或小于 28px 的可见交互热区。
- 边界：离线、空空间、上传取消、SSE 取消/重试、503、API Key 401/恢复。
- Lighthouse：Performance 100、Accessibility 100、Best Practices 100；CLS 0.004。
- 固定负载连续两轮：最慢读 p95 57.68/41.43ms、最慢本地写 p95 84.23/45.35ms、错误率均为 0。

```bash
python scripts/browser_acceptance.py
python loadtest/dry_run.py -n 30 -c 4 --output target/acceptance/performance.json
```

## Docker

- 三份 Compose `config --quiet` 通过。
- 推荐 Chroma 栈从空卷构建，Flyway 初始化后完成严格 Mock smoke 和固定评估。
- Chroma 停止/重启后无需重新嵌入即可命中原 document/chunk 引用。
- Chroma 集合级备份/恢复会校验行数和 SHA256，恢复只允许写入不存在的新集合。
- Milvus 2.5.27 使用 Strong 一致性完成真实契约，不逐批强制 flush。
- 应用非 root、只读根文件系统、2 CPU/1GiB 资源限制、健康检查和回环端口生效。

## 安全门

- CI Trivy 扫描 `CRITICAL/HIGH` 依赖、secrets 和最终镜像 OS 包，不忽略无修复条目。
- 最终生产 SBOM 的 Java HIGH/CRITICAL 为 0；Alpine 3.23.5 镜像 73 个 OS 包的 HIGH/CRITICAL 为 0；9 个 Netty 模块均为 4.1.136.Final。
- 本地终验运行 secret 模式扫描、`git diff --check` 和 Git 状态审计。
- API Key 错配/公开绑定错配会拒绝启动；安全头和请求 ID 有自动测试。

## 已知边界

- 本地单用户，不宣称多租户 RBAC。
- Mock 指标不代表真实模型质量；真实模型需重跑同一评估集。
- Milvus Compose 是单机，不宣称集群高可用。
- 飞书已由 fake server 覆盖故障协议；真实租户需用最小权限做上线前演练。
