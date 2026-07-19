#Requires -Version 5.1
$ErrorActionPreference = "Stop"

$Root = Resolve-Path (Join-Path $PSScriptRoot "..")
Set-Location $Root

if (-not (Test-Path ".env")) {
  Copy-Item ".env.example" ".env"
  Write-Host "Created .env (APP_RAG_PROVIDER=mock, keys optional)."
}

Write-Host "Starting docker compose (mock provider, seeded data/docs)..."
docker compose up -d --build

$baseUrl = if ($env:RAG_SMOKE_BASE_URL) { $env:RAG_SMOKE_BASE_URL } else { "http://localhost:8080" }
Write-Host "Running mock demo smoke..."
node scripts/smoke-mock-demo.mjs $baseUrl
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$port = if ($env:APP_HOST_PORT) { $env:APP_HOST_PORT } else { "8080" }
Write-Host @"

Mock demo is up.
  Web UI:  http://localhost:${port}/
  Health:  http://localhost:${port}/api/health

Try asking: 「RAG 中的向量检索是怎么工作的？」
Upload more files via 知识库 panel to extend the demo.
Stop: docker compose down
"@
