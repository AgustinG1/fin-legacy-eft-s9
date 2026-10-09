param(
    [Parameter(Mandatory=$true)][string]$DireccionBroker,
    [switch]$SoloValidar
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
if ($DireccionBroker -notmatch '^([A-Za-z0-9][A-Za-z0-9.-]*):([0-9]{1,5})$') {
    throw 'Usa host:puerto, sin http://, rutas ni credenciales.'
}
if ([int]$Matches[2] -lt 1 -or [int]$Matches[2] -gt 65535) { throw 'Puerto fuera de rango.' }
$previousBroker=$env:KAFKA_BOOTSTRAP_REMOTO
Push-Location $projectRoot
try {
    $env:KAFKA_BOOTSTRAP_REMOTO=$DireccionBroker
    $composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'),
        '-f',(Join-Path $projectRoot 'compose.kafka.yaml'),
        '-f',(Join-Path $projectRoot 'compose.reportes.yaml'),
        '-f',(Join-Path $projectRoot 'compose.kafka-remoto.yaml'))
    Invoke-EftCompose config --quiet
    if ($SoloValidar) {
        Write-Output 'Configuracion valida. No se iniciaron ni cambiaron contenedores.'
        return
    }
    # Requiere la infraestructura local existente. No inicia ni detiene el broker local.
    Invoke-EftCompose up -d --no-deps --wait --wait-timeout 180 pagos auditoria
    Write-Output 'Pagos y auditoria configurados para Kafka remoto. Falta verificar un evento real en AWS.'
} finally {
    $env:KAFKA_BOOTSTRAP_REMOTO=$previousBroker
    Pop-Location
}
