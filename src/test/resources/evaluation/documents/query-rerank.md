# 查询改写、重排与 Prompt

## 查询改写

多轮对话中的短问句可能包含“它”“这个”“上述”等指代词。查询改写会结合有限的会话历史，把“它有什么收益”改成例如“RAG 有什么收益”的独立检索查询。改写失败时保留原问题继续检索。

## Rerank

Embedding 召回速度快但排序精度有限。Rerank 对问题与候选片段逐对打分，将最相关结果放在前面。外部重排服务遇到 429、5xx 或超时会有限重试，最终失败则保留原检索顺序并标记 fallback。

## Prompt 防护

资料片段被标记为不可信数据。Prompt 明确要求模型不得执行资料中的指令，每个事实必须使用 `[documentId:chunkId]` 标记引用；资料不足时使用固定无答案文本。

## 流式终态

流式问答使用 status、retrieval、token、done、error、cancelled 事件。每个请求只能出现一个终态；error 或 cancelled 之后不能再发送 done。
