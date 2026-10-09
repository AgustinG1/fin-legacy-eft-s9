$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) { $dockerPath = $dockerCommand.Source }
else {
    $dockerPath = Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe'
    if (-not (Test-Path -LiteralPath $dockerPath)) { throw 'Docker no se encuentra en PATH ni en la instalacion local de Docker Desktop.' }
}
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path logs | Out-Null
    if (-not (Test-Path -LiteralPath '.env')) {
        $localPassword = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
        @("DB_PASSWORD=$localPassword",'EFT_INTEREST_RATE=0.05','EFT_INTEREST_PERIOD=TRIMESTRAL-DEMO') | Set-Content -LiteralPath '.env' -Encoding utf8
    }
    foreach ($line in Get-Content -LiteralPath '.env') {
        $parts = $line.Split('=',2)
        if ($parts.Length -eq 2 -and $parts[0] -in @('DB_PASSWORD','EFT_INTEREST_RATE','EFT_INTEREST_PERIOD')) {
            [Environment]::SetEnvironmentVariable($parts[0],$parts[1],'Process')
        }
    }
    & $dockerPath compose config --quiet
    if ($LASTEXITCODE -ne 0) { throw 'Configuracion Compose invalida.' }
    & $dockerPath compose up -d --wait db
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL no pudo iniciar.' }
    & java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres --eft.output-directory=output/postgres *> logs/postgres-batch.log
    if ($LASTEXITCODE -ne 0) { throw 'Fallo Batch en PostgreSQL. Revisa logs/postgres-batch.log.' }
    & $dockerPath compose exec -T db psql -U eft -d eft -c 'SELECT kind,COUNT(*) AS filas FROM legacy_row GROUP BY kind ORDER BY kind;' *> logs/postgres-conteos.log
    if ($LASTEXITCODE -ne 0) { throw 'No se pudieron verificar los conteos.' }
    Get-Content -LiteralPath logs/postgres-conteos.log
    Write-Output 'PostgreSQL verificado. La base queda disponible en localhost:5433; credencial local en .env (ignorado por Git).'
} finally { Pop-Location }
