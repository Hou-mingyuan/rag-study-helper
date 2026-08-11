# 向量检索与 Rerank — 演示文档

## 向量检索怎么工作

向量检索（Vector Search）把用户问题和文档 chunk 都转成 Embedding 向量，用**余弦相似度**或内积衡量语义相近程度，返回得分最高的 top-K 条。

在本项目中默认先召回 **top 20**，再经 Rerank 保留 **top 5** 喂给 LLM。

## 分数阈值

检索结果会过滤低于 `score-threshold` 的片段。Mock 模式下阈值自动放宽（约 0.12），便于本地 hash 向量演示；生产环境建议 0.75–0.85。

## Rerank 的作用

Embedding 召回速度快但精度有限；Rerank 模型（如 BGE-reranker）对「问题–文档对」精细打分，把最相关段落排在前面，减少噪声 token、提高回答质量。

Mock 模式下 Rerank 使用词重叠启发式，不调用外部 API。

## 与 InMemory / Chroma / Milvus 的关系

- **InMemory**：零依赖，适合 demo 与 CI。
- **Chroma / Milvus**：持久化向量，适合多实例与生产。

演示 compose 默认 InMemory + Mock Provider，一条 `docker compose up` 即可体验「入库 → 检索 → 问答」闭环。
