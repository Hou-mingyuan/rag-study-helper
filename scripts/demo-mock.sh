#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
SHARED_INFRA="${RAG_SHARED_INFRA_DIR:-$ROOT/../shared-infra}"
SHARED_COMPOSE="$SHARED_INFRA/docker-compose.yml"

if [[ ! -f "$SHARED_COMPOSE" ]]; then
  echo "shared-infra not found at $SHARED_INFRA. Set RAG_SHARED_INFRA_DIR to its directory." >&2
  exit 1
fi

if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env with local Mock defaults."
fi

echo "Starting shared MySQL, Redis and Chroma..."
docker compose --project-directory "$SHARED_INFRA" -f "$SHARED_COMPOSE" \
  --profile study up -d --wait mysql redis chroma

echo "Starting RAG Study Helper on 19050..."
docker compose -f docker-compose-chroma.yml up -d --build --wait --wait-timeout 360

BASE_URL="${RAG_SMOKE_BASE_URL:-http://127.0.0.1:19050}"
if ! node scripts/smoke-mock-demo.mjs "$BASE_URL"; then
  docker compose -f docker-compose-chroma.yml logs app
  exit 1
fi

cat <<EOF

RAG Study Helper is ready.
  Web UI:    ${BASE_URL}/
  Health:    ${BASE_URL}/api/health
  Readiness: ${BASE_URL}/api/readiness

Stop app: docker compose -f docker-compose-chroma.yml down
EOF
