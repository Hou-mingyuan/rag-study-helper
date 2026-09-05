#Requires -Version 5.1
param([string]$Destination = (Join-Path $PSScriptRoot "..\..\shared-infra"))
$ErrorActionPreference = "Stop"
$source = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot "..\infra\shared-infra"))
$target = [IO.Path]::GetFullPath($Destination)
if (Test-Path -LiteralPath $target) {
  throw "Destination already exists: $target. Review its VERSION and configuration before upgrading."
}
Copy-Item -LiteralPath $source -Destination $target -Recurse
Write-Host "Installed shared-infra $(Get-Content (Join-Path $target 'VERSION')) at $target"
Write-Host "Start: docker compose --project-directory `"$target`" --profile study up -d --wait mysql redis chroma"
