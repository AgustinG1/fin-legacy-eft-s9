Add-Type -AssemblyName System.Net.Http
$http=[System.Net.Http.HttpClient]::new()
$http.Timeout=[TimeSpan]::FromSeconds(15)
$checks=[System.Collections.Generic.List[object]]::new()
function ApiResponse($response) {
    $content=$response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
    $body=$null
    if ($content) { try { $body=$content | ConvertFrom-Json } catch {} }
    $upstream=$null
    if ($response.Headers.Contains('X-EFT-Upstream-clientes')) { $upstream=($response.Headers.GetValues('X-EFT-Upstream-clientes') | Select-Object -First 1) }
    $revision=$null
    if ($response.Headers.Contains('X-EFT-Config')) { $revision=($response.Headers.GetValues('X-EFT-Config') | Select-Object -First 1) }
    return [pscustomobject]@{Status=[int]$response.StatusCode;Body=$body;Text=$content;Bytes=[Text.Encoding]::UTF8.GetByteCount($content);Upstream=$upstream;Revision=$revision}
}
function Api($method,$url,$token,$body,$basic=$null) {
    $request=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::new($method),$url)
    $request.Headers.Accept.ParseAdd('application/json')
    if ($token) { $request.Headers.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Bearer',$token) }
    if ($basic) { $request.Headers.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',$basic) }
    if ($null -ne $body) { $request.Content=[System.Net.Http.StringContent]::new(($body | ConvertTo-Json -Depth 12 -Compress),[Text.Encoding]::UTF8,'application/json') }
    try { $response=$http.SendAsync($request).GetAwaiter().GetResult();try { return ApiResponse $response } finally {$response.Dispose()} }
    finally { $request.Dispose() }
}
function ApiToken($client,$secret,$scope) {
    $request=[System.Net.Http.HttpRequestMessage]::new([System.Net.Http.HttpMethod]::Post,'http://localhost:9100/oauth2/token')
    $basic=[Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes("${client}:$secret"))
    $request.Headers.Authorization=[System.Net.Http.Headers.AuthenticationHeaderValue]::new('Basic',$basic)
    $request.Content=[System.Net.Http.StringContent]::new(('grant_type=client_credentials&scope='+[Uri]::EscapeDataString($scope)),[Text.Encoding]::UTF8,'application/x-www-form-urlencoded')
    try { $response=$http.SendAsync($request).GetAwaiter().GetResult();try { return ApiResponse $response } finally {$response.Dispose()} }
    finally { $request.Dispose() }
}
function ApiCheck($name,$actual,$expected) {
    $ok=if ($expected -is [int] -or $expected -is [decimal]) { [decimal]$actual -eq [decimal]$expected } else { "$actual" -ceq "$expected" }
    $checks.Add([pscustomobject]@{prueba=$name;esperado=$expected;obtenido=$actual;aprobado=$ok})
    if (-not $ok) { throw "Fallo '$name': esperado $expected, obtenido $actual" }
    Write-Output "OK: $name"
}
