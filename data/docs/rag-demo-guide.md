# RAG Study Helper 演示文档

本文件用于 **Mock 模式**（无需 Chat / Embedding API Key）下的上传→检索→问答闭环演示。

## 核心概念

RAG（Retrieval-Augmented Generation）= 检索增强生成。流程为：

1. 将文档分块并向量化写入向量库
2. 用户提问时，把问题转为向量并检索相似片段
3. 将检索到的片段作为上下文，交给大模型生成回答

## 本项目的三个关键模块

- **Embedding**：把文本转为向量（Mock 模式下使用本地哈希向量）
- **向量库**：InMemory / Chroma / Milvus 存储分块向量
- **Chat**：基于上下文流式回答（Mock 模式下引用检索片段生成演示回复）

## 演示问答示例

- 问：「RAG 的核心概念是什么？」→ 应命中本文「核心概念」段落
- 问：「向量库有哪些选项？」→ 应命中「三个关键模块」段落
- 问：「文档里提到了哪些核心概念？」→ 同上

## 入库说明

启动时 `DocumentIngestionService` 会自动扫描 `data/docs/` 目录。上传接口 `/api/documents/upload` 同样可用。
