# ADR-0001: V2 Runtime and Reliability Architecture

- Status: Accepted
- Date: 2026-07-19
- Owners: RAG Study Helper maintainers
- Scope: `rag-study-helper` v2 release candidate

## Context

The current mainline uses Java 8, Spring Boot 2.6.13, LangChain4j 0.35.0, an unversioned two-table schema, and direct `EmbeddingStore` calls. It cannot safely implement knowledge-space isolation, observable ingestion tasks, vector/database compensation, protected Feishu deletion, trustworthy citations, or current dependency security fixes.

The previous `RFC.md` proposed keeping Java 8 on main while implementing Java 17 on a separate branch. That would leave two partial architectures and does not satisfy the v2 completion criteria.

Maven Central metadata checked on 2026-07-19 shows:

- Spring Boot latest: 4.1.0; latest maintained 3.x patch: 3.5.16.
- LangChain4j core/OpenAI: 1.18.0.
- LangChain4j Chroma, Milvus, and Apache PDFBox integrations: 1.18.0-beta28 in the same release train.
- MyBatis-Plus Boot 3 starter: 3.5.17.
- Redisson latest 3.x: 3.52.0; latest major: 4.6.1.
- Apache POI: 5.5.1; JSoup: 1.22.2.

## Decision

### Runtime and dependencies

The only maintained mainline will use:

| Component | Decision |
|---|---|
| Java baseline | Java 17 bytecode and runtime contract |
| Spring | Spring Boot 3.5.16 / Spring Framework 6 |
| AI core | LangChain4j 1.18.0 |
| AI integrations | Chroma/Milvus/PDFBox 1.18.0-beta28, pinned and contract-tested |
| Persistence | MySQL 8.4 LTS, Flyway 11.20.3 migrations, MyBatis-Plus Boot 3 starter 3.5.17 |
| Redis | Redis 7, Spring Data Redis, Redisson 3.52.0 |
| Document parsing | Apache POI 5.5.1, JSoup 1.22.2, current PDFBox integration |
| Build quality | Maven Surefire + Failsafe + JaCoCo; no integration-test exclusion |

Java 17 is the deployment baseline even though development may run on JDK 21. Spring Boot 4 is not selected because this migration already changes the servlet namespace, AI APIs, persistence, and vector protocols; adding Spring Framework 7 provides no required product capability and increases migration risk. Redisson 4 is deferred for the same reason.

Java 8 and Spring Boot 2 code paths will not remain in main. There is no compatibility profile, duplicate module, commented rollback implementation, or legacy Docker image in the supported architecture.

### Product boundary and access control

V2 remains a local single-user learning assistant, not a multi-tenant SaaS product.

- Native startup binds to `127.0.0.1` by default.
- Docker publishes ports on `127.0.0.1` only.
- Public exposure requires an explicit configuration flag and a non-empty API key; invalid combinations fail startup.
- Knowledge spaces isolate documents, vectors, sessions, tasks, and Feishu sync configuration even in single-user mode.
- Existing endpoints remain compatible by resolving an omitted `spaceId` to the seeded default space. New clients use explicit `/api/spaces/{spaceId}/...` resources.

### Persistence and migrations

Flyway becomes the only schema authority.

- `V1` represents the existing documents/chunks baseline for clean databases.
- Existing non-empty databases are baselined at version 1, then upgraded by `V2` migrations.
- Existing documents are assigned to a seeded default knowledge space.
- New tables record knowledge spaces, ingestion tasks, Feishu sync runs/state, chat-session metadata, vector index metadata, and vector reconciliation work.
- Foreign keys, unique keys, state checks, and optimistic versions enforce document/chunk/task ownership and legal transitions.

`init.sql` is no longer mounted by Compose. Migration and seed execution happen at application startup through Flyway.

### Ingestion and vector consistency

Ingestion uses one state machine for upload, directory scan, Feishu, update, retry, cancel, and delete:

```text
QUEUED -> PARSING -> EMBEDDING -> INDEXING -> COMPLETED
             |           |          |
             +-----------+----------+-> FAILED -> RETRYING
             +--------------------------> CANCELLED
```

- Idempotency is scoped by knowledge space and source identity/content hash.
- Document/chunk rows are staged before vector activation.
- Vector IDs are deterministic and stored in MySQL.
- A new version becomes searchable only after every expected vector write succeeds.
- Old versions remain searchable until the replacement is active.
- Failed writes remove known partial vectors; failed cleanup is persisted as reconciliation work and retried.
- Delete first makes a document non-searchable in MySQL, then removes vectors; failed removal remains visible and retryable rather than corrupting the user-visible state.
- Retrieval discards vector hits not backed by an active MySQL chunk, preventing stale vectors from leaking into answers.

### Vector-store boundary

Application services depend on a project-owned `VectorStoreGateway`, not directly on LangChain4j stores. InMemory, Chroma, and Milvus implement the same add/search/remove/health/dimension contract.

The application persists store type, collection schema version, embedding model, and dimension. Startup fails on a mismatch and points to the explicit re-embedding command. A v2 collection name is used for upgraded external stores; old collections are never silently mutated or deleted.

### Feishu deletion safety

Feishu sync is complete-success driven:

- Base URL, timeouts, page size, rate, retry policy, and deletion guard are configurable and testable against a fake server.
- Every page and recursive branch must complete; partial enumeration is a failed run.
- A distributed lock prevents concurrent runs across instances.
- Incremental comparison uses update time and the last successful run state.
- Any listing/content failure blocks all remote-delete application for that run.
- Missing nodes become deletion candidates. Deletion requires two consecutive complete successful runs.
- Zero-remote, absolute-count, and percentage thresholds block suspicious batches.
- Deletion is scoped to one Feishu remote space and one local knowledge space.
- Every run exposes counts, failure reasons, retry activity, and deletion-guard decisions without logging credentials or document text.

### RAG and citations

- Query rewrite has bounded history, timeout, validation, and fallback to the original query.
- Retrieval is knowledge-space filtered and returns stable document/chunk identifiers and scores.
- Rerank status and score are visible; external failure uses a declared fallback.
- Prompt assembly treats retrieved text as untrusted data, enforces a token/character budget, and refuses unsupported answers by default.
- Citations reference `documentId`, `chunkId`, `chunkIndex`, and available page/section metadata; UI navigation opens the exact chunk.
- Sessions are server-owned. Redis appends a complete turn atomically, while MySQL stores session metadata for listing and ownership.
- SSE has request IDs, explicit retrieval/status/error/done events, cancellation state, bounded timeouts, and consistent retry behavior.

### Frontend and verification

The UI remains a compact static web application served by Spring Boot, but source JavaScript/CSS receives a real lint/typecheck/test/build gate. Runtime third-party assets are bundled locally. The design direction is a restrained study workspace focused on spaces, sources, tasks, citations, and repeated use rather than a generic chat landing page.

Completion requires:

- Unit and integration tests with a non-negotiable JaCoCo threshold for core packages.
- A fixed 20+ question offline evaluation with Hit@k, MRR, citation correctness, and no-answer metrics.
- Fake Feishu tests for create/update/delete/pagination/429/timeout and deletion protection.
- The same vector contract tests for all adapters, plus persistent Chroma smoke.
- Mock API E2E, real Chromium at 375x812, 768x1024, and 1440x900, Docker smoke, multi-instance, performance, backup/restore, dependency, secret, and Git diff checks.
- Project-owned host ports stay within 19050-19059. MySQL, Redis, Chroma and MinIO reuse shared-infra; its existing loopback ports are explicitly outside this project allocation.

## Migration impact

### Configuration mapping

| V1 key | V2 key / behavior |
|---|---|
| `server.port=8080` | retained inside the container; Compose publishes host `19050` |
| `spring.redis.*` | `spring.data.redis.*` (Boot 3 namespace), with shared Redis DB 3 |
| `vector.store.type` | retained |
| `milvus.dimension` | `app.vector.dimension`, validated against index metadata |
| `app.api-key.*` | retained, with startup validation for public exposure |
| `app.feishu.*` | retained and extended with base URL/retry/rate/delete guard settings |
| implicit global documents | seeded default knowledge space |

Existing JSON response fields (`resCode`, `msg`, `obj`) remain during v2 migration. HTTP status codes become truthful. Existing document/chat routes resolve to the default space; new space-scoped routes are canonical.

### Data and vectors

- Existing MySQL metadata is migrated in place and assigned to the default space.
- Existing chunk text allows safe re-embedding without the original uploaded file.
- InMemory vectors are rebuilt at startup from active chunks.
- Chroma/Milvus use new versioned collections and an explicit re-embedding job. Old collections remain untouched until an operator verifies and removes them.

## Rejected alternatives

- **Keep Java 8 and update only tests:** rejected because supported AI/vector clients and Spring security/observability remain blocked.
- **Maintain Java 8 and Java 17 branches in one repository:** rejected because it preserves two partial architectures and doubles validation.
- **Jump directly to Spring Boot 4/Redisson 4:** rejected as unrelated major-version risk with no acceptance benefit.
- **Use vector storage as the source of truth:** rejected because vector systems cannot provide the transactional ownership, task, and audit semantics required here.
- **Delete every Feishu node missing from one listing:** rejected because partial listings, permission changes, rate limits, and timeouts make absence unsafe evidence.
- **Introduce a full SPA framework:** rejected because the product can meet its workflows with a small bundled frontend and a much smaller build/runtime surface.

## Consequences

- This is an intentional one-way mainline migration. It requires Java 17 and a database backup before upgrading an existing installation.
- The first build will require adapting LangChain4j 0.35 APIs and `javax` imports.
- Chroma/Milvus integration artifacts retain beta-suffixed versions, so project-owned contracts and pinned server images are mandatory.
- More schema and state-machine code is added, but failures become observable, retryable, and testable instead of silently corrupting data.

## Rollback

Rollback is operational, not a second code path:

1. Stop v2 before running destructive maintenance.
2. Restore the pre-upgrade MySQL/Redis backup and use the untouched v1 vector collection/volume.
3. Run the previously released artifact from an external release/tag.

This repository does not retain v1 runtime code or automatically delete v1 data.
