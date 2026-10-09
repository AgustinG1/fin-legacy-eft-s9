param([switch]$ProbarCaida,[switch]$ProbarBalanceo)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
$composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'))
. (Join-Path $PSScriptRoot 'http-local.ps1')
$values=Read-EftEnvironment
$evidence=Join-Path $projectRoot 'evidencias/canales'
New-Item -ItemType Directory -Force $evidence | Out-Null
$stopped=$false
$replicaStarted=$false
Push-Location $projectRoot
try {
    $basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("infra:$($values.INFRA_PASSWORD)"))
    ApiCheck 'Config protegido sin credencial' (Api GET 'http://localhost:8888/bff-web/default' $null $null).Status 401
    $config=Api GET 'http://localhost:8888/bff-web/default' $null $null $basic
    ApiCheck 'Config autorizado' $config.Status 200
    $sources=@($config.Body.propertySources)
    ApiCheck 'Revision centralizada disponible' ($sources[0].source.'eft.config.revision') 's9-cloud-v1'
    ApiCheck 'Eureka protegido sin credencial' (Api GET 'http://localhost:8761/eureka/apps' $null $null).Status 401
    for ($i=0;$i -lt 90;$i++) {
        $registry=Api GET 'http://localhost:8761/eureka/apps' $null $null $basic
        $names=@($registry.Body.applications.application | ForEach-Object {$_.name})
        if (@($names | Where-Object {$_ -in @('CLIENTES','CUENTAS','PAGOS','BFF-WEB','BFF-MOVIL','BFF-CAJERO')}).Count -eq 6) {break}
        Start-Sleep -Seconds 1
    }
    ApiCheck 'Seis aplicaciones registradas' (@($names | Where-Object {$_ -in @('CLIENTES','CUENTAS','PAGOS','BFF-WEB','BFF-MOVIL','BFF-CAJERO')}).Count) 6
    $operator=(ApiToken 'eft-operador' $values.OPERATOR_SECRET 'clientes.read clientes.write cuentas.read cuentas.write pagos.read pagos.write').Body.access_token
    $web=(ApiToken 'canal-web' $values.WEB_CHANNEL_SECRET 'web.read web.write').Body.access_token
    $mobile=(ApiToken 'canal-movil' $values.MOVIL_CHANNEL_SECRET 'movil.read movil.write').Body.access_token
    $atm=(ApiToken 'canal-cajero' $values.CAJERO_CHANNEL_SECRET 'cajero.read cajero.write').Body.access_token
    $atmReader=(ApiToken 'canal-cajero' $values.CAJERO_CHANNEL_SECRET 'cajero.read').Body.access_token
    ApiCheck 'Canal movil no obtiene permiso cajero' (ApiToken 'canal-movil' $values.MOVIL_CHANNEL_SECRET 'cajero.write').Status 400
    ApiCheck 'Web sin token' (Api GET 'http://localhost:8201/api/web/perfil' $null $null).Status 401
    ApiCheck 'Token movil no entra a web' (Api GET 'http://localhost:8201/api/web/perfil' $mobile $null).Status 401
    ApiCheck 'Token web no accede directamente a cuentas' (Api GET 'http://localhost:8102/api/cuentas' $web $null).Status 401
    $customer=@{id=$values.DEMO_CUSTOMER_ID;documento="DEMO-$($values.DEMO_CUSTOMER_ID)";nombre='Cliente demo canales';email='canales@example.test'}
    ApiCheck 'Perfil demo preparado' (Api POST 'http://localhost:8101/api/clientes' $operator $customer).Status 201
    $account=@{id=$values.DEMO_ACCOUNT_ID;clienteId=$values.DEMO_CUSTOMER_ID;tipo='CORRIENTE';alias='Demo canales'}
    ApiCheck 'Cuenta demo preparada' (Api POST 'http://localhost:8102/api/cuentas' $operator $account).Status 201
    $accountUrl="http://localhost:8102/api/cuentas/$($values.DEMO_ACCOUNT_ID)"
    $current=(Api GET $accountUrl $operator $null).Body.saldo
    $difference=100000-[decimal]$current
    if ($difference -ne 0) {
        $payment=@{id=[guid]::NewGuid().ToString();concepto='Preparacion datos sinteticos canales';monto=[math]::Abs($difference)}
        if ($difference -gt 0) {$payment.tipo='DEPOSITO';$payment.destino=$values.DEMO_ACCOUNT_ID}
        else {$payment.tipo='RETIRO';$payment.origen=$values.DEMO_ACCOUNT_ID}
        ApiCheck 'Saldo demo preparado en 100000' (Api POST 'http://localhost:8103/api/pagos' $operator $payment).Status 200
    }
    $webProfile=Api GET 'http://localhost:8201/api/web/perfil' $web $null
    $summary=Api GET 'http://localhost:8202/api/movil/resumen' $mobile $null
    ApiCheck 'Perfil web completo' $webProfile.Status 200
    ApiCheck 'Resumen movil disponible' $summary.Status 200
    ApiCheck 'Cliente identificado por token' $webProfile.Body.cliente.id $values.DEMO_CUSTOMER_ID
    ApiCheck 'Movil omite documento y correo' (@($summary.Body.PSObject.Properties.Name | Where-Object {$_ -in @('email','documento','cliente')}).Count) 0
    ApiCheck 'Respuesta movil menor que web' ($summary.Bytes -lt $webProfile.Bytes) $true
    ApiCheck 'BFF carga revision de Config' $webProfile.Revision 's9-cloud-v1'
    $webProfile.Text | Set-Content (Join-Path $evidence 'respuesta-web.json') -Encoding utf8
    $summary.Text | Set-Content (Join-Path $evidence 'respuesta-movil.json') -Encoding utf8
    [pscustomobject]@{web_bytes=$webProfile.Bytes;movil_bytes=$summary.Bytes} | Export-Csv (Join-Path $evidence 'tamano-respuestas.csv') -NoTypeInformation -Encoding UTF8
    $atmUrl="http://localhost:8203/api/cajero/cuentas/$($values.DEMO_ACCOUNT_ID)"
    ApiCheck 'Consulta saldo cajero' (Api GET "$atmUrl/saldo" $atm $null).Body.saldo 100000
    $foreignCustomer=[guid]::NewGuid().ToString();$foreignAccount=[guid]::NewGuid().ToString()
    ApiCheck 'Otro cliente de prueba' (Api POST 'http://localhost:8101/api/clientes' $operator @{id=$foreignCustomer;documento="DEMO-$foreignCustomer";nombre='Otro demo';email='otro@example.test'}).Status 201
    ApiCheck 'Cuenta ajena de prueba' (Api POST 'http://localhost:8102/api/cuentas' $operator @{id=$foreignAccount;clienteId=$foreignCustomer;tipo='AHORRO';alias='Ajena'}).Status 201
    ApiCheck 'Cajero rechaza cuenta ajena' (Api GET "http://localhost:8203/api/cajero/cuentas/$foreignAccount/saldo" $atm $null).Status 403
    ApiCheck 'Web rechaza cargo a cuenta ajena' (Api POST 'http://localhost:8201/api/web/pagos' $web @{id=[guid]::NewGuid().ToString();tipo='RETIRO';origen=$foreignAccount;monto=1000;concepto='Prueba ajena'}).Status 403
    ApiCheck 'Limite de retiro cajero' (Api POST "$atmUrl/retiros" $atm @{id=[guid]::NewGuid().ToString();monto=201000}).Status 400
    ApiCheck 'Denominacion de retiro cajero' (Api POST "$atmUrl/retiros" $atm @{id=[guid]::NewGuid().ToString();monto=1500}).Status 400
    $withdraw=@{id=[guid]::NewGuid().ToString();monto=10000}
    ApiCheck 'Lectura cajero no permite retiro' (Api POST "$atmUrl/retiros" $atmReader $withdraw).Status 403
    ApiCheck 'Retiro cajero completo' (Api POST "$atmUrl/retiros" $atm $withdraw).Body.estado 'COMPLETADO'
    ApiCheck 'Repeticion retiro cajero' (Api POST "$atmUrl/retiros" $atm $withdraw).Body.estado 'COMPLETADO'
    ApiCheck 'Retiro repetido deja saldo 90000' (Api GET "$atmUrl/saldo" $atm $null).Body.saldo 90000
    if ($ProbarBalanceo) {
        Invoke-EftCompose --profile escala up -d --wait --wait-timeout 180 clientes-replica
        $replicaStarted=$true
        for($i=0;$i -lt 90;$i++) {
            $registered=Api GET 'http://localhost:8761/eureka/apps/CLIENTES' $null $null $basic
            if (@($registered.Body.application.instance | Where-Object {$_.status -eq 'UP'}).Count -eq 2) {break}
            Start-Sleep -Seconds 1
        }
        ApiCheck 'Dos instancias de clientes registradas' (@($registered.Body.application.instance | Where-Object {$_.status -eq 'UP'}).Count) 2
        $instances=[System.Collections.Generic.HashSet[string]]::new()
        $samples=[System.Collections.Generic.List[object]]::new()
        # Eureka's read-only response cache and clients' local registry update asynchronously.
        # Observe routing, rather than assuming registration instantly updates every client.
        for($i=0;$i -lt 40;$i++) {
            $balanced=Api GET 'http://localhost:8201/api/web/perfil' $web $null
            ApiCheck "Balanceo respuesta $i" $balanced.Status 200
            if ($balanced.Upstream) {[void]$instances.Add($balanced.Upstream)}
            $samples.Add([pscustomobject]@{solicitud=$i;instancia_clientes=$balanced.Upstream;cliente=$balanced.Body.cliente.id})
            if($instances.Count -ge 2 -and $i -ge 7) {break}
            Start-Sleep -Seconds 3
        }
        $samples | Export-Csv (Join-Path $evidence 'balanceo.csv') -NoTypeInformation -Encoding UTF8
        ApiCheck 'LoadBalancer utilizo ambas instancias' $instances.Count 2
        Invoke-EftCompose stop clientes-replica
        $replicaStarted=$false
    }
    if ($ProbarCaida) {
        Invoke-EftCompose stop cuentas
        $stopped=$true
        for($i=0;$i -lt 4;$i++) {ApiCheck "Caida de cuentas $i devuelve 503" (Api GET "$atmUrl/saldo" $atm $null).Status 503}
        ApiCheck 'Circuito abierto responde 503' (Api GET "$atmUrl/saldo" $atm $null).Status 503
        Invoke-EftCompose start cuentas
        for($i=0;$i -lt 90;$i++) {
            try {if ((Api GET 'http://localhost:8102/actuator/health' $null $null).Status -eq 200) {break}} catch {}
            Start-Sleep -Seconds 1
        }
        $stopped=$false
        Start-Sleep -Seconds 20
        for($i=0;$i -lt 40;$i++) {$restored=Api GET "$atmUrl/saldo" $atm $null;if($restored.Status -eq 200){break};Start-Sleep -Seconds 1}
        ApiCheck 'Cajero recupera servicio de cuentas' $restored.Status 200
        ApiCheck 'Caida no altera saldo' $restored.Body.saldo 90000
    }
    Write-Output "Verificacion canales terminada: $($checks.Count) comprobaciones aprobadas."
} finally {
    if($stopped){Invoke-EftCompose start cuentas}
    if($replicaStarted){Invoke-EftCompose stop clientes-replica}
    $checks | Export-Csv (Join-Path $evidence 'comprobaciones-http.csv') -NoTypeInformation -Encoding UTF8
    $http.Dispose()
    Pop-Location
}
