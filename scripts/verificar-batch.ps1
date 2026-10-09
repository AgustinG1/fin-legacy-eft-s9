param([switch]$OmitirCompilacion)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path 'logs' | Out-Null
    if (-not $OmitirCompilacion) {
        & mvn -B verify *> logs/pruebas-batch.log
        if ($LASTEXITCODE -ne 0) { throw 'Fallaron las pruebas. Revisa logs/pruebas-batch.log.' }
    }
    & java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo *> logs/ejecucion-batch.log
    if ($LASTEXITCODE -ne 0) { throw 'Fallo la ejecucion. Revisa logs/ejecucion-batch.log.' }
    Get-ChildItem -LiteralPath output -Recurse -Filter control.csv | ForEach-Object {
        Write-Output $_.FullName
        Import-Csv -LiteralPath $_.FullName | Format-Table
    }
    Write-Output 'Verificacion terminada. La tasa y el periodo usados son ejemplos de prueba.'
} finally { Pop-Location }
