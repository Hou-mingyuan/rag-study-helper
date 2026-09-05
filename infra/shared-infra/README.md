# Shared Local Infrastructure

Contract version: **1.0.0**. Source of truth: `rag-study-helper/infra/shared-infra`
in https://github.com/Hou-mingyuan/rag-study-helper (use the same checkout as the
application). This bundle replaces the previously undocumented external repository.
It is for a trusted local Docker host; its credentials are development fixtures.

From the RAG repository, run `pwsh scripts/setup-shared-infra.ps1`. PowerShell 5.1
on Windows is also supported. The installer creates the sibling `shared-infra`
directory and refuses to overwrite an existing installation.

From the installed directory:

```sh
docker compose --profile study config --quiet
docker compose --profile study up -d --wait mysql redis chroma
docker compose ps
```

| Service | Pinned image | Host endpoint | Consumers |
| --- | --- | --- | --- |
| MySQL | mysql:8.4.8 | 127.0.0.1:13306 | rag_study_helper / peizhen_app |
| Redis | redis:7.4.8-alpine | 127.0.0.1:16379 | Peizhen DB 1; RAG DB 3 |
| Chroma | chromadb/chroma:0.4.24 | 127.0.0.1:18000 | RAG v1 HTTP API compatibility |

Containers are named `infra-mysql`, `infra-redis`, and `infra-chroma` on network
`devnet`. Inside Docker use ports 3306, 6379, and 8000. RAG uses user `rag` with
password `rag-local-app-password`; Peizhen uses `peizhen` / `peizhen-local`.
The MySQL root password defaults to `root` and can be set by `MYSQL_ROOT_PASSWORD`.

The init SQL runs only on an empty volume. To add these application databases to
an existing development server, review and apply `mysql-init/01-app-databases.sql`
explicitly as an administrator; do not delete volumes to rerun initialization.
Application Flyway migrations own their tables. Named volumes persist service
data across `down`; `down -v` destroys shared data for both applications.

For the optional RAG Milvus path, run `docker compose --profile storage up -d
--wait mysql redis minio`. MinIO is pinned to `RELEASE.2025-04-22T22-12-26Z` and
named `infra-minio`, with API/console on loopback ports 19000/19001. Defaults are
`ruoyi` / `ruoyi123`, matching RAG's Milvus Compose file; use the same
`SHARED_MINIO_ACCESS_KEY` / `SHARED_MINIO_SECRET_KEY` in both projects when changing
them. Milvus owns its `rag-study-helper-milvus` bucket. Chroma remains the default.
There is no Docker engine on the remediation host, so a fresh service startup
remains an environment acceptance step, not a claimed local pass.
