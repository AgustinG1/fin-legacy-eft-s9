param(
    [Parameter(Mandatory=$true)][string]$DireccionBroker,
    [switch]$EsperarRecuperacion
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
. (Join-Path $PSScriptRoot 'http-local.ps1')
if ($DireccionBroker -notmatch '^([A-Za-z0-9][A-Za-z0-9.-]*):([0-9]{1,5})$') {
    throw 'Indica host:puerto de Kafka en el laboratorio.'
}
if ([int]$Matches[2] -lt 1 -or [int]$Matches[2] -gt 65535) {throw 'Puerto fuera de rango.'}
if ($Matches[1] -in @('localhost','127.0.0.1','kafka','host.docker.internal')) {
    throw 'La comprobacion AWS requiere la direccion del broker remoto.'
}
$composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'),'-f',(Join-Path $projectRoot 'compose.kafka.yaml'))
$evidence=Join-Path $projectRoot ('evidencias/aws/'+[DateTime]::Now.ToString('yyyyMMdd-HHmmss')+'-'+[guid]::NewGuid().ToString('N').Substring(0,8))
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
function DbScalar($database,$sql) {
    $result=& $dockerPath @composeFiles exec -T db psql -X -U eft -d $database -At -v ON_ERROR_STOP=1 -c $sql
    if ($LASTEXITCODE -ne 0) {throw 'Fallo la consulta de comprobacion.'}
    return "$result".Trim()
}
Push-Location $projectRoot
try {
    $running=& $dockerPath @composeFiles ps --status running -q kafka
    if ($LASTEXITCODE -ne 0) {throw 'No se pudo comprobar Kafka local.'}
    ApiCheck 'Broker local detenido' ([string]::IsNullOrWhiteSpace(($running -join ''))) 'True'
    foreach ($service in @('pagos','auditoria')) {
        $configured=& $dockerPath @composeFiles exec -T $service printenv KAFKA_BOOTSTRAP
        if ($LASTEXITCODE -ne 0) {throw "No se pudo comprobar $service"}
        ApiCheck "$service usa el broker indicado" ("$configured".Trim()) $DireccionBroker
    }
    $values=Read-EftEnvironment
    $issued=ApiToken 'eft-operador' $values.OPERATOR_SECRET 'clientes.read clientes.write cuentas.read cuentas.write pagos.read pagos.write auditoria.read'
    ApiCheck 'Autorizacion para la prueba' $issued.Status 200
    $token=$issued.Body.access_token
    $customer=[guid]::NewGuid().ToString();$account=[guid]::NewGuid().ToString()
    ApiCheck 'Cliente sintetico creado' (Api POST 'http://localhost:8101/api/clientes' $token @{id=$customer;documento="LAB-$customer";nombre='Prueba laboratorio';email='lab@example.test'}).Status 201
    ApiCheck 'Cuenta sintetica creada' (Api POST 'http://localhost:8102/api/cuentas' $token @{id=$account;clienteId=$customer;tipo='CORRIENTE';alias='Laboratorio'}).Status 201
    $payment=@{id=[guid]::NewGuid().ToString();tipo='DEPOSITO';origen=$null;destino=$account;monto=100;concepto='Prueba sintetica laboratorio'}
    $result=Api POST 'http://localhost:8103/api/pagos' $token $payment
    ApiCheck 'Pago completado' $result.Body.estado 'COMPLETADO'
    if ($EsperarRecuperacion) {
        ApiCheck 'Evento pendiente con broker detenido' (DbScalar eft_pagos "SELECT count(*) FROM payment_outbox WHERE event_id='$($payment.id)' AND published_at IS NULL") 1
        Write-Output 'Pago completado y evento pendiente. Ahora inicia Kafka en EC2 con: sudo docker start eft-kafka'
    }
    $audit=$null
    for ($attempt=0;$attempt -lt 180;$attempt++) {
        $response=Api GET "http://localhost:8104/api/auditoria/$($payment.id)" $token $null
        if ($response.Status -eq 200) {$audit=$response;break}
        if ($response.Status -notin @(404,503)) {throw "Auditoria devolvio $($response.Status)"}
        Start-Sleep -Seconds 1
    }
    if ($null -eq $audit) {throw 'El evento no llego en tres minutos. Revisa el broker remoto y su grupo de seguridad.'}
    ApiCheck 'Evento recibido desde el broker remoto' $audit.Body.evento.id $payment.id
    ApiCheck 'Importe recibido' $audit.Body.evento.comprobante.monto 100
    $published='0'
    for ($attempt=0;$attempt -lt 20;$attempt++) {
        $published=DbScalar eft_pagos "SELECT count(*) FROM payment_outbox WHERE event_id='$($payment.id)' AND published_at IS NOT NULL"
        if ($published -eq '1') {break}
        Start-Sleep -Seconds 1
    }
    ApiCheck 'Outbox confirmado' $published 1
    $repeated=Api POST 'http://localhost:8103/api/pagos' $token $payment
    ApiCheck 'Repeticion conserva comprobante' $repeated.Body.comprobante.fecha $result.Body.comprobante.fecha
    ApiCheck 'Saldo sin doble deposito' (Api GET "http://localhost:8102/api/cuentas/$account" $token $null).Body.saldo 100
    $audit.Body | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $evidence 'evento.json') -Encoding utf8
    Write-Output "Verificacion remota aprobada. Resultados: $evidence"
} finally {
    $checks | Export-Csv (Join-Path $evidence 'verificacion.csv') -NoTypeInformation -Encoding utf8
    $http.Dispose()
    Pop-Location
}
