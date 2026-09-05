# Mock 演示

## 目标

在不配置任何模型 Key 的情况下，复用 shared-infra 的持久化 Chroma、MySQL 和 Redis，完成：启动、目录扫描、异步入库、检索、SSE 回答、精确引用和会话回读。

## 启动

Windows：

```powershell
./scripts/demo-mock.ps1
```

Linux/macOS：

```bash
./scripts/demo-mock.sh
```

首次构建通常需要 3-5 分钟；镜像网络较慢时会更久。严格 smoke 成功后才会显示入口地址。

## 页面路线

1. 打开 `http://127.0.0.1:19050`，确认右上角显示 `MOCK 评估模式`。
2. 选择 Default 空间，打开“资料与分块”。
3. 内置演示资料已由 smoke 扫描；点击任一文档检查分块和完整正文。
4. 回到“带引用问答”，提问“RAG 的核心流程是什么？”。
5. 等待状态从检索、重排、生成进入完成，点击引用定位实际分块。
6. 新建会话并追问“它为什么需要 Rerank？”，观察查询改写和服务端会话历史。
7. 打开“任务与同步”，查看入库终态、向量  `active/indexed` 计数和飞书关闭状态。

## 自动证明

```bash
node scripts/smoke-mock-demo.mjs http://127.0.0.1:19050
```

该命令不会把失败重试成重复写操作。它只在默认空间无文档时触发一次扫描，等待任务完成，再验证：

- health/readiness 和 Mock provider；
- 静态页面 CSP；
- 至少一份已入库文档；
- `status/retrieval/token/done` SSE 生命周期；
- 每条检索来源包含 document/chunk ID；
- 回答包含可落地引用；
- Redis 会话包含一问一答；
- 临时 smoke 会话已删除。

任何断言失败都会非零退出并打印可处理原因。

## 固定质量评估

```bash
node scripts/evaluate-mock.mjs \
  --base-url http://127.0.0.1:19050 \
  --out target/acceptance/evaluation.json
```

评估会创建独立的时间戳空间，上传 5 份固定资料并运行 27 个问题。空间保留用于检查和性能验收；需要清理时在页面确认后删除。

## 停止

```bash
docker compose -f docker-compose-chroma.yml down
```

该命令只停止应用，不停止共享基础设施。即使附加 `-v` 也只删除本项目 inbox 卷，不会删除共享 MySQL、Redis 或 Chroma 数据。
