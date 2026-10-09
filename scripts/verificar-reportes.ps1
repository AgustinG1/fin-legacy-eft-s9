param([switch]$TodasSemanas)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
. (Join-Path $PSScriptRoot 'http-local.ps1')
$composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'),'-f',(Join-Path $projectRoot 'compose.kafka.yaml'),'-f',(Join-Path $projectRoot 'compose.reportes.yaml'))
$values=Read-EftEnvironment
$evidence=Join-Path $projectRoot $(if($TodasSemanas){'evidencias/reportes'}else{'evidencias/semana3/reportes'})
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
Push-Location $projectRoot
try {
    $issued=ApiToken 'eft-operador' $values.OPERATOR_SECRET 'reportes.read web.migracion'
    ApiCheck 'Operador obtiene consulta Batch' $issued.Status 200
    $token=$issued.Body.access_token
    ApiCheck 'Reportes exige token' (Api GET 'http://localhost:8105/api/reportes/resumen' $null $null).Status 401
    $summary=Api GET 'http://localhost:8105/api/reportes/resumen' $token $null
    ApiCheck 'Resumen Batch disponible' $summary.Status 200
    $expected=if($TodasSemanas){@{DAILY=@(1020,400,613,7);INTEREST=@(1016,430,580,6);ANNUAL=@(1018,312,699,7)}}else{@{DAILY=@(1000,392,608,0);INTEREST=@(1000,421,578,1);ANNUAL=@(1000,304,696,0)}}
    foreach($kind in @('DAILY','INTEREST','ANNUAL')) {
        $row=@($summary.Body | Where-Object { $_.proceso -eq $kind -and $_.entrada -eq $expected[$kind][0] } | Sort-Object ejecucion -Descending)[0]
        if(-not $row) {throw "Falta el resultado $kind"}
        ApiCheck "$kind entradas" $row.entrada $expected[$kind][0]
        ApiCheck "$kind validos unicos" $row.validosUnicos $expected[$kind][1]
        ApiCheck "$kind rechazados" $row.rechazados $expected[$kind][2]
        ApiCheck "$kind duplicados" $row.duplicadosValidos $expected[$kind][3]
        if($kind -eq 'DAILY') {$daily=$row}
        if($kind -eq 'INTEREST') {
            ApiCheck 'Periodo conserva configuracion de simulacion' $row.periodo $values.EFT_INTEREST_PERIOD
            ApiCheck 'Tasa conserva configuracion de simulacion' ([decimal]$row.tasa) ([decimal]::Parse($values.EFT_INTEREST_RATE,[Globalization.CultureInfo]::InvariantCulture))
        }
    }
    $path="/api/reportes/$($daily.dataset)/DAILY"
    $first=Api GET "http://localhost:8105${path}?pagina=0&tamano=1" $token $null
    $second=Api GET "http://localhost:8105${path}?pagina=1&tamano=1" $token $null
    ApiCheck 'Primera pagina contiene una fila' @($first.Body.filas).Count 1
    ApiCheck 'Segunda pagina contiene una fila' @($second.Body.filas).Count 1
    $a=$first.Body.filas[0];$b=$second.Body.filas[0]
    ApiCheck 'Paginas conservan posiciones diferentes' ([bool]("$($a.source_file):$($a.line_number)" -ne "$($b.source_file):$($b.line_number)")) 'True'
    ApiCheck 'Tamano excesivo rechazado' (Api GET "http://localhost:8105${path}?tamano=101" $token $null).Status 400
    ApiCheck 'Dataset sin ejecucion no disponible' (Api GET ("http://localhost:8105/api/reportes/"+('0'*64)+'/DAILY') $token $null).Status 404
    $web=$null
    for($attempt=0;$attempt -lt 60;$attempt++) {
        $web=Api GET 'http://localhost:8201/api/web/migracion/resumen' $token $null
        if($web.Status -eq 200) {break}
        if($web.Status -ne 503) {throw "BFF web devolvio $($web.Status)"}
        Start-Sleep -Seconds 2
    }
    ApiCheck 'BFF web consulta servicio reportes' $web.Status 200
    ApiCheck 'Web conserva cantidad de datasets' @($web.Body).Count @($summary.Body).Count
    foreach($kind in @('DAILY','INTEREST','ANNUAL')) {
        $selected=@($summary.Body | Where-Object { $_.proceso -eq $kind -and $_.entrada -eq $expected[$kind][0] } | Sort-Object ejecucion -Descending)[0]
        $page=Api GET "http://localhost:8201/api/web/migracion/$($selected.dataset)/${kind}?pagina=0&tamano=2" $token $null
        ApiCheck "$kind datos migrados visibles desde BFF" $page.Status 200
        ApiCheck "$kind BFF preserva dataset de origen" $page.Body.resumen.dataset $selected.dataset
        ApiCheck "$kind BFF entrega dos registros validos" @($page.Body.filas).Count 2
        $page.Body | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $evidence "bff-$kind.json") -Encoding utf8
    }
    $channel=ApiToken 'canal-web' $values.WEB_CHANNEL_SECRET 'web.read'
    ApiCheck 'Cliente de canal no ve migracion administrativa' (Api GET 'http://localhost:8201/api/web/migracion/resumen' $channel.Body.access_token $null).Status 403
    $ErrorActionPreference='Continue'
    try {
        & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft -v ON_ERROR_STOP=1 -c 'SET ROLE eft_reportes; UPDATE legacy_row SET status=status WHERE 1=0;' *> (Join-Path $evidence 'escritura-denegada.txt')
        $deniedCode=$LASTEXITCODE
    } finally {$ErrorActionPreference='Stop'}
    ApiCheck 'Base impide escritura al rol de consulta' ([bool]($deniedCode -ne 0 -and ((Get-Content (Join-Path $evidence 'escritura-denegada.txt') -Raw) -match 'permission denied'))) 'True'
    $summary.Body | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $evidence 'resumen.json') -Encoding utf8
    $first.Body | ConvertTo-Json -Depth 10 | Set-Content (Join-Path $evidence 'pagina-ejemplo.json') -Encoding utf8
} finally {
    $checks | Export-Csv (Join-Path $evidence 'verificacion-http.csv') -NoTypeInformation -Encoding utf8
    $http.Dispose();Pop-Location
}
