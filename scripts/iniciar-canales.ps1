param([switch]$SinConstruir)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path logs | Out-Null
    if (-not (Test-Path -LiteralPath '.env')) { 'EFT_INTEREST_RATE=0.05','EFT_INTEREST_PERIOD=TRIMESTRAL-DEMO' | Set-Content '.env' -Encoding utf8 }
    $values=Read-EftEnvironment
    foreach ($name in @('INFRA_PASSWORD','WEB_CHANNEL_SECRET','MOVIL_CHANNEL_SECRET','CAJERO_CHANNEL_SECRET','WEB_INTERNAL_SECRET','MOVIL_INTERNAL_SECRET','CAJERO_INTERNAL_SECRET')) {
        if (-not $values.ContainsKey($name)) {
            $values[$name]=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
            Add-Content -LiteralPath '.env' -Value "$name=$($values[$name])" -Encoding utf8
        }
    }
    foreach ($name in @('DEMO_CUSTOMER_ID','DEMO_ACCOUNT_ID')) {
        if (-not $values.ContainsKey($name)) {
            $values[$name]=[guid]::NewGuid().ToString()
            Add-Content -LiteralPath '.env' -Value "$name=$($values[$name])" -Encoding utf8
        }
    }
    & (Join-Path $PSScriptRoot 'preparar-https.ps1')
    & (Join-Path $PSScriptRoot 'iniciar-servicios.ps1') -SinConstruir:$SinConstruir
    $composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'))
    Invoke-EftCompose config --quiet
    if (-not $SinConstruir) { Invoke-EftCompose build config-server discovery-server bff-web bff-movil bff-cajero }
    Invoke-EftCompose up -d --wait --wait-timeout 300 config-server discovery-server auth-server clientes cuentas pagos bff-web bff-movil bff-cajero
    Write-Output 'Canales iniciados: web 8201, movil 8202, cajero 8203; Config 8888 y Eureka 8761. Ejecuta verificar-canales.ps1.'
} finally { Pop-Location }
