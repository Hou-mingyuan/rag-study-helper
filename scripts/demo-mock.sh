#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env (APP_RAG_PROVIDER=mock, keys optional)."
fi

echo "Starting docker compose (mock provider, seeded data/docs)..."
docker compose up -d --build

echo "Running mock demo smoke..."
node scripts/smoke-mock-demo.mjs "${RAG_SMOKE_BASE_URL:-http://localhost:8080}"

cat <<EOF

Mock demo is up.
  Web UI:  http://localhost:${APP_HOST_PORT:-8080}/
  Health:  http://localhost:${APP_HOST_PORT:-8080}/api/health

Try asking: 「RAG 中的向量检索是怎么工作的？」
Upload more files via 知识库 panel to extend the demo.
Stop: docker compose down
EOF
