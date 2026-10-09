param([switch]$ProbarCaida)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
. (Join-Path $PSScriptRoot 'http-local.ps1')
$composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'),'-f',(Join-Path $projectRoot 'compose.kafka.yaml'))
$values=Read-EftEnvironment
$evidence=Join-Path $projectRoot 'evidencias/eventos'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$brokerStopped=$false
function Audit($id,$token,$minimum=1) {
    for($attempt=0;$attempt -lt 90;$attempt++) {
        $result=Api GET "http://localhost:8104/api/auditoria/$id" $token $null
        if($result.Status -eq 200 -and $result.Body.entregas -ge $minimum) {return $result}
        if($result.Status -notin @(200,404,503)) {throw "Auditoria devolvio $($result.Status)"}
        Start-Sleep -Seconds 1
    }
    throw 'El evento no aparecio en auditoria dentro del plazo.'
}
function DbScalar($database,$sql) {
    $result=& $dockerPath @composeFiles exec -T db psql -X -U eft -d $database -At -v ON_ERROR_STOP=1 -c $sql
    if($LASTEXITCODE -ne 0) {throw 'Fallo la consulta de evidencia.'}
    return "$result".Trim()
}
function SendEvent($key,$value) {
    "$key|$value" | & $dockerPath @composeFiles exec -T -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' kafka /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --topic eft.pagos.v1 --property parse.key=true --property 'key.separator=|'
    if($LASTEXITCODE -ne 0) {throw 'No se pudo enviar el evento de prueba.'}
}
Push-Location $projectRoot
try {
    $issued=ApiToken 'eft-operador' $values.OPERATOR_SECRET 'clientes.read clientes.write cuentas.read cuentas.write pagos.read pagos.write auditoria.read'
    ApiCheck 'Token con permiso de auditoria' $issued.Status 200
    $token=$issued.Body.access_token
    ApiCheck 'Auditoria exige autenticacion' (Api GET "http://localhost:8104/api/auditoria/$([guid]::NewGuid())" $null $null).Status 401
    $restricted=ApiToken 'eft-operador' $values.OPERATOR_SECRET 'pagos.read'
    ApiCheck 'Token de pagos no habilita audiencia de auditoria' (Api GET "http://localhost:8104/api/auditoria/$([guid]::NewGuid())" $restricted.Body.access_token $null).Status 401
    $customer=[guid]::NewGuid().ToString();$account=[guid]::NewGuid().ToString()
    ApiCheck 'Cliente sintetico' (Api POST 'http://localhost:8101/api/clientes' $token @{id=$customer;documento="KAFKA-$customer";nombre='Prueba Kafka';email='kafka@example.test'}).Status 201
    ApiCheck 'Cuenta sintetica' (Api POST 'http://localhost:8102/api/cuentas' $token @{id=$account;clienteId=$customer;tipo='CORRIENTE';alias='Kafka'}).Status 201
    $payment=@{id=[guid]::NewGuid().ToString();tipo='DEPOSITO';origen=$null;destino=$account;monto=100;concepto='Prueba sintetica Kafka'}
    $result=Api POST 'http://localhost:8103/api/pagos' $token $payment
    ApiCheck 'Pago completado' $result.Body.estado 'COMPLETADO'
    $audit=Audit $payment.id $token
    ApiCheck 'Evento corresponde al pago' $audit.Body.evento.id $payment.id
    ApiCheck 'Monto auditado' $audit.Body.evento.comprobante.monto 100
    ApiCheck 'Outbox confirmado tras publicar' (DbScalar eft_pagos "SELECT count(*) FROM payment_outbox WHERE event_id='$($payment.id)' AND published_at IS NOT NULL") 1
    ApiCheck 'Repeticion conserva fecha del comprobante' (Api POST 'http://localhost:8103/api/pagos' $token $payment).Body.comprobante.fecha $result.Body.comprobante.fecha
    ApiCheck 'Repeticion no duplica saldo' (Api GET "http://localhost:8102/api/cuentas/$account" $token $null).Body.saldo 100
    SendEvent $payment.id ($audit.Body.evento | ConvertTo-Json -Depth 12 -Compress)
    $replayed=Audit $payment.id $token 2
    ApiCheck 'Reentrega detectada' $replayed.Body.entregas 2
    ApiCheck 'Un solo evento almacenado' (DbScalar eft_auditoria "SELECT count(*) FROM audit_event WHERE event_id='$($payment.id)'") 1
    ApiCheck 'Reentrega no ejecuta otro pago' (Api GET "http://localhost:8102/api/cuentas/$account" $token $null).Body.saldo 100
    $invalid=[guid]::NewGuid().ToString()
    SendEvent $invalid 'not-json'
    Start-Sleep -Seconds 4
    $ErrorActionPreference='Continue'
    try {
        $dead=& $dockerPath @composeFiles exec -T -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' kafka /opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --topic eft.pagos.v1.DLT --from-beginning --timeout-ms 7000 --property print.key=true --property 'key.separator=|' 2> logs/kafka-dlt-consumer.log
    } finally {$ErrorActionPreference='Stop'}
    # ConsoleConsumer exits on its bounded timeout; only an actual matching record proves recovery.
    ApiCheck 'Evento invalido separado en DLT' ([bool](@($dead) -match [regex]::Escape("$invalid|not-json"))) 'True'
    ApiCheck 'Evento invalido no entra a auditoria' (Api GET "http://localhost:8104/api/auditoria/$invalid" $token $null).Status 404
    if($ProbarCaida) {
        Invoke-EftCompose stop kafka
        $brokerStopped=$true
        $pending=@{id=[guid]::NewGuid().ToString();tipo='DEPOSITO';origen=$null;destino=$account;monto=7;concepto='Caida sintetica Kafka'}
        ApiCheck 'Pago sigue funcionando sin broker' (Api POST 'http://localhost:8103/api/pagos' $token $pending).Body.estado 'COMPLETADO'
        Start-Sleep -Seconds 8
        ApiCheck 'Evento persistido pendiente' (DbScalar eft_pagos "SELECT count(*) FROM payment_outbox WHERE event_id='$($pending.id)' AND published_at IS NULL") 1
        ApiCheck 'Evento pendiente aun no auditado' (Api GET "http://localhost:8104/api/auditoria/$($pending.id)" $token $null).Status 404
        Invoke-EftCompose up -d --wait --wait-timeout 180 kafka
        $brokerStopped=$false
        $recovered=Audit $pending.id $token
        ApiCheck 'Evento recuperado automaticamente' $recovered.Body.evento.id $pending.id
        ApiCheck 'Outbox recuperado confirmado' (DbScalar eft_pagos "SELECT count(*) FROM payment_outbox WHERE event_id='$($pending.id)' AND published_at IS NOT NULL") 1
        ApiCheck 'Saldo tras recuperacion' (Api GET "http://localhost:8102/api/cuentas/$account" $token $null).Body.saldo 107
    }
    $audit.Body | ConvertTo-Json -Depth 12 | Set-Content (Join-Path $evidence 'evento-ejemplo.json') -Encoding utf8
    Invoke-EftCompose exec -T -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --describe | Set-Content (Join-Path $evidence 'topics.txt') -Encoding utf8
} finally {
    if($brokerStopped) {Invoke-EftCompose up -d --wait --wait-timeout 180 kafka}
    $checks | Export-Csv (Join-Path $evidence 'verificacion-http.csv') -NoTypeInformation -Encoding utf8
    $http.Dispose();Pop-Location
}
