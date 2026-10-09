param([switch]$ProbarCaida)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
Add-Type -AssemblyName System.Net.Http
$http = [System.Net.Http.HttpClient]::new()
$http.Timeout = [TimeSpan]::FromSeconds(15)
$checks = [System.Collections.Generic.List[object]]::new()
$values = Read-EftEnvironment
$accountsStopped = $false
$evidence = Join-Path $projectRoot 'evidencias/servicios'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
function New-Request($method,$url,$token,$body) {
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method),$url)
    if ($token) { $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token) }
    if ($null -ne $body) {
        $request.Content = [System.Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 12 -Compress),[Text.Encoding]::UTF8,'application/json')
    }
    return $request
}
function Read-Response($response) {
    $content = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $data = $null
    if ($content) { try { $data = $content | ConvertFrom-Json } catch { $data = $null } }
    return [pscustomobject]@{ Status=[int]$response.StatusCode; Body=$data }
}
function Invoke-Api($method,$url,$token,$body) {
    $request = New-Request $method $url $token $body
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        try { return Read-Response $response } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Token($client,$secret,$scope) {
    $basic = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${client}:$secret"))
    $request = [System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,'http://localhost:9100/oauth2/token')
    $request.Headers.Authorization = [System.Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',$basic)
    $request.Content = [System.Net.Http.StringContent]::new(('grant_type=client_credentials&scope='+[Uri]::EscapeDataString($scope)),[Text.Encoding]::UTF8,'application/x-www-form-urlencoded')
    try {
        $response = $http.SendAsync($request).GetAwaiter().GetResult()
        try { return Read-Response $response } finally { $response.Dispose() }
    } finally { $request.Dispose() }
}
function Check($name,$actual,$expected) {
    $ok = if ($expected -is [int] -or $expected -is [decimal] -or $expected -is [double]) {
        [decimal]$actual -eq [decimal]$expected
    } else { "$actual" -ceq "$expected" }
    $checks.Add([pscustomobject]@{ prueba=$name; esperado=$expected; obtenido=$actual; aprobado=$ok })
    if (-not $ok) { throw "Fallo '$name': esperado $expected, obtenido $actual" }
    Write-Output "OK: $name"
}
function Payment($kind,$from,$to,$amount) {
    return @{ id=[guid]::NewGuid().ToString(); tipo=$kind; origen=$from; destino=$to; monto=$amount; concepto='Verificacion EFT - datos sinteticos' }
}
function Balance($id,$token) { return (Invoke-Api GET "http://localhost:8102/api/cuentas/$id" $token $null).Body.saldo }
Push-Location $projectRoot
try {
    $issued = Token 'eft-operador' $values.OPERATOR_SECRET 'clientes.read clientes.write cuentas.read cuentas.write pagos.read pagos.write'
    Check 'OAuth2 emite token de operador' $issued.Status 200
    $operatorToken = $issued.Body.access_token
    $readerToken = (Token 'eft-consulta' $values.CONSULTA_SECRET 'clientes.read cuentas.read pagos.read').Body.access_token
    $ledgerToken = (Token 'pagos-interno' $values.PAGOS_INTERNAL_SECRET 'ledger.write').Body.access_token
    Check 'Secreto incorrecto' (Token 'eft-operador' 'secreto-incorrecto' 'clientes.read').Status 401
    Check 'Consulta no obtiene permisos de escritura' (Token 'eft-consulta' $values.CONSULTA_SECRET 'pagos.write').Status 400
    Check 'Sin token' (Invoke-Api GET 'http://localhost:8101/api/clientes' $null $null).Status 401
    Check 'Token invalido' (Invoke-Api GET 'http://localhost:8101/api/clientes' 'invalido' $null).Status 401
    Check 'Audiencia incorrecta' (Invoke-Api GET 'http://localhost:8101/api/clientes' $ledgerToken $null).Status 401
    $customerId = [guid]::NewGuid().ToString()
    $customer = @{id=$customerId;documento="DEMO-$customerId";nombre='Cliente sintetico EFT';email='demo@example.test'}
    Check 'Consulta no crea clientes' (Invoke-Api POST 'http://localhost:8101/api/clientes' $readerToken $customer).Status 403
    Check 'Alta de cliente' (Invoke-Api POST 'http://localhost:8101/api/clientes' $operatorToken $customer).Status 201
    Check 'Consulta de cliente' (Invoke-Api GET "http://localhost:8101/api/clientes/$customerId" $readerToken $null).Status 200
    $profileUpdate = @{nombre='Cliente actualizado';email='actualizado@example.test';version=0}
    Check 'Actualizacion de perfil' (Invoke-Api PUT "http://localhost:8101/api/clientes/$customerId" $operatorToken $profileUpdate).Status 200
    Check 'Rechazo de perfil desactualizado' (Invoke-Api PUT "http://localhost:8101/api/clientes/$customerId" $operatorToken $profileUpdate).Status 409
    $accountIds = @()
    foreach ($alias in @('Origen','Destino','Concurrencia','Cierre')) {
        $id = [guid]::NewGuid().ToString(); $accountIds += $id
        $account = @{id=$id;clienteId=$customerId;tipo='CORRIENTE';alias=$alias}
        Check "Apertura cuenta $alias" (Invoke-Api POST 'http://localhost:8102/api/cuentas' $operatorToken $account).Status 201
    }
    $origin=$accountIds[0]; $destination=$accountIds[1]; $concurrent=$accountIds[2]; $closing=$accountIds[3]
    $missing = @{id=[guid]::NewGuid().ToString();clienteId=[guid]::NewGuid().ToString();tipo='AHORRO';alias='Inexistente'}
    Check 'No abre cuenta de cliente inexistente' (Invoke-Api POST 'http://localhost:8102/api/cuentas' $operatorToken $missing).Status 400
    $deposit = Payment DEPOSITO $null $origin 100
    Check 'Operador no accede al ledger interno' (Invoke-Api POST 'http://localhost:8102/interno/movimientos' $operatorToken $deposit).Status 403
    Check 'Deposito por servicio de pagos' (Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $deposit).Status 200
    $transfer = Payment TRANSFERENCIA $origin $destination 40
    $first = Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $transfer
    Check 'Transferencia completada' $first.Body.estado 'COMPLETADO'
    $repeated = Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $transfer
    Check 'Reintento conserva comprobante' $repeated.Body.comprobante.fecha $first.Body.comprobante.fecha
    Check 'Saldo origen tras repetir' (Balance $origin $operatorToken) 60
    Check 'Saldo destino tras repetir' (Balance $destination $operatorToken) 40
    $transfer.monto=41
    Check 'ID repetido con otro monto' (Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $transfer).Status 409
    $withdraw = Payment RETIRO $origin $null 61
    Check 'Sobregiro rechazado' (Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $withdraw).Status 409
    Check 'Sobregiro no cambia saldo' (Balance $origin $operatorToken) 60
    Check 'Cierre con saldo rechazado' (Invoke-Api POST "http://localhost:8102/api/cuentas/$origin/cierre?version=2" $operatorToken $null).Status 409
    Check 'Cierre de cuenta vacia' (Invoke-Api POST "http://localhost:8102/api/cuentas/$closing/cierre?version=0" $operatorToken $null).Status 200
    Check 'Cuenta cerrada no acepta deposito' (Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken (Payment DEPOSITO $null $closing 5)).Status 409
    Check 'Deposito para concurrencia' (Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken (Payment DEPOSITO $null $concurrent 100)).Status 200
    $one = New-Request POST 'http://localhost:8103/api/pagos' $operatorToken (Payment RETIRO $concurrent $null 70)
    $two = New-Request POST 'http://localhost:8103/api/pagos' $operatorToken (Payment RETIRO $concurrent $null 70)
    try {
        $task1=$http.SendAsync($one); $task2=$http.SendAsync($two)
        $response1=$task1.GetAwaiter().GetResult(); $response2=$task2.GetAwaiter().GetResult()
        try { $codes=@([int]$response1.StatusCode,[int]$response2.StatusCode) | Sort-Object }
        finally { $response1.Dispose(); $response2.Dispose() }
        Check 'Retiros simultaneos: uno aprobado y uno rechazado' ($codes -join ',') '200,409'
        Check 'Saldo tras concurrencia' (Balance $concurrent $operatorToken) 30
    } finally { $one.Dispose(); $two.Dispose() }
    if ($ProbarCaida) {
        $recoverable = Payment DEPOSITO $null $origin 7
        Invoke-EftCompose stop cuentas
        $accountsStopped=$true
        $failure=Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $recoverable
        Check 'Dependencia detenida responde 503' $failure.Status 503
        Check 'Fallo conserva solicitud pendiente' (Invoke-Api GET "http://localhost:8103/api/pagos/$($recoverable.id)" $operatorToken $null).Body.estado 'PENDIENTE'
        Invoke-EftCompose start cuentas
        for ($i=0; $i -lt 90; $i++) {
            try { if ((Invoke-Api GET 'http://localhost:8102/actuator/health' $null $null).Status -eq 200) { break } } catch { }
            Start-Sleep -Seconds 1
        }
        $accountsStopped=$false
        # A healthy process may still be absent from another client's cached Eureka registry.
        # Recover with the original ID until routing and circuit state have also recovered.
        for ($i=0; $i -lt 90; $i++) {
            $recovered = Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $recoverable
            if ($recovered.Status -eq 200 -and $recovered.Body.estado -eq 'COMPLETADO') { break }
            if ($recovered.Status -ne 503) { throw "La recuperacion devolvio HTTP $($recovered.Status), se esperaba 200 o 503." }
            Start-Sleep -Seconds 1
        }
        Check 'Reintento tras recuperar cuentas' $recovered.Body.estado 'COMPLETADO'
        Check 'Repeticion tras recuperar cuentas' (Invoke-Api POST 'http://localhost:8103/api/pagos' $operatorToken $recoverable).Body.estado 'COMPLETADO'
        Check 'Recuperacion aplica deposito una sola vez' (Balance $origin $operatorToken) 67
    }
    $transferId=$transfer.id
    & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft_cuentas -c "SELECT operation_id,COUNT(*) AS asientos,SUM(delta) AS suma FROM ledger_entry WHERE operation_id='$transferId' GROUP BY operation_id;" > (Join-Path $evidence 'ledger-postgres.txt')
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo consultar evidencia del ledger.' }
    & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft_pagos -c "SELECT event_id,event_type,published_at FROM payment_outbox WHERE event_id='$transferId';" > (Join-Path $evidence 'outbox-postgres.txt')
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo consultar evidencia del outbox.' }
    $entryCount = & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft_cuentas -At -c "SELECT COUNT(*) FROM ledger_entry WHERE operation_id='$transferId';"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar el ledger.' }
    Check 'Transferencia conserva dos asientos en PostgreSQL' $entryCount 2
    $net = & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft_cuentas -At -c "SELECT SUM(delta) FROM ledger_entry WHERE operation_id='$transferId';"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar el saldo contable.' }
    Check 'Transferencia conserva suma contable cero' $net 0
    $eventCount = & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft_pagos -At -c "SELECT COUNT(*) FROM payment_outbox WHERE event_id='$transferId';"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar el evento.' }
    Check 'Transferencia tiene un unico evento pendiente' $eventCount 1
    $crossDatabase = & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft -At -c "SELECT has_database_privilege('eft_clientes','eft_cuentas','CONNECT');"
    if ($LASTEXITCODE -ne 0) { throw 'No se pudo comprobar el aislamiento.' }
    Check 'Clientes no puede conectarse a base de cuentas' $crossDatabase 'f'
    Write-Output "Verificacion terminada: $($checks.Count) comprobaciones aprobadas. Datos sinteticos conservados en PostgreSQL."
} finally {
    if ($accountsStopped) { Invoke-EftCompose start cuentas }
    $checks | Export-Csv -LiteralPath (Join-Path $evidence 'comprobaciones-http.csv') -NoTypeInformation -Encoding UTF8
    $http.Dispose()
    Pop-Location
}
