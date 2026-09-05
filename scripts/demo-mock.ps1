#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root
$SharedCandidate = if ($env:RAG_SHARED_INFRA_DIR) {
  $env:RAG_SHARED_INFRA_DIR
} else {
  Join-Path $Root "..\shared-infra"
}
$SharedInfra = [System.IO.Path]::GetFullPath($SharedCandidate)
$SharedCompose = Join-Path $SharedInfra "docker-compose.yml"

if (-not (Test-Path $SharedCompose)) {
  throw "shared-infra not found at $SharedInfra. Set RAG_SHARED_INFRA_DIR to its directory."
}

if (-not (Test-Path ".env")) {
  Copy-Item ".env.example" ".env"
  Write-Host "Created .env with local Mock defaults."
}

Write-Host "Starting shared MySQL, Redis and Chroma..."
docker compose --project-directory $SharedInfra -f $SharedCompose --profile study up -d --wait mysql redis chroma
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

Write-Host "Starting RAG Study Helper on 19050..."
docker compose -f docker-compose-chroma.yml up -d --build --wait --wait-timeout 360
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$baseUrl = if ($env:RAG_SMOKE_BASE_URL) { $env:RAG_SMOKE_BASE_URL } else { "http://127.0.0.1:19050" }
node scripts/smoke-mock-demo.mjs $baseUrl
if ($LASTEXITCODE -ne 0) {
  docker compose -f docker-compose-chroma.yml logs app
  exit $LASTEXITCODE
}

Write-Host @"

RAG Study Helper is ready.
  Web UI:    ${baseUrl}/
  Health:    ${baseUrl}/api/health
  Readiness: ${baseUrl}/api/readiness

Stop app: docker compose -f docker-compose-chroma.yml down
"@
