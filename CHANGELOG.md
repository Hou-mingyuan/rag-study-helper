# Changelog

All notable changes to this project are documented in this file.

## [1.0.0] - 2026-07-04

### Added

- Enterprise RAG Q&A on Spring Boot 2.6 + LangChain4j 0.35 (JDK 8 compatible)
- Multi-turn chat with Redis-backed session store and SSE streaming UI
- Document ingestion: web upload, directory scan, Feishu Wiki sync
- Hybrid retrieval pipeline: embedding search → BGE rerank → LLM answer with citations
- Vector store options: InMemory, Chroma 0.4.24, Milvus 2.3.x via docker-compose profiles
- Distributed rate limiting (`@RateLimit` + Redisson token bucket + daily cap)
- MySQL metadata persistence (documents + chunk mapping) with SHA256 deduplication
- Unit tests for RAG pipeline, Feishu sync, rerank, and global exception handling
- `VERSION`, `CHANGELOG.md`, and `USAGE.md` for open-source release

### Changed

- Desensitized default configs: no hardcoded DB passwords or Feishu space IDs in docs
- Prepared for public release under `Hou-mingyuan/rag-study-helper`
