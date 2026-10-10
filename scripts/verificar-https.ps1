$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
. (Join-Path $PSScriptRoot 'http-local.ps1')
$evidence=Join-Path $projectRoot 'evidencias/https'
New-Item -ItemType Directory -Force -Path $evidence | Out-Null
$wrongTrust=Join-Path $projectRoot ('runtime/tls-prueba-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $wrongTrust | Out-Null
$certificates=[System.Collections.Generic.List[object]]::new()
try {
    foreach ($entry in @(@('bff-web',8201,'/api/web/perfil'),@('bff-movil',8202,'/api/movil/resumen'),@('bff-cajero',8203,'/api/cajero/cuentas/prueba/saldo'))) {
        $name=$entry[0];$port=$entry[1];$route=$entry[2]
        ApiCheck "$name HTTPS con certificado esperado" (Api GET "https://localhost:$port/actuator/health" $null $null).Status 200
        ApiCheck "$name exige token sobre HTTPS" (Api GET "https://localhost:$port$route" $null $null).Status 401
        $plainRejected=$false
        try {$plainRejected=(Api GET "http://localhost:$port/actuator/health" $null $null).Status -eq 400} catch {$plainRejected=$true}
        ApiCheck "$name rechaza HTTP sin cifrar" $plainRejected $true
        $other=if($name -eq 'bff-web'){'bff-movil'}else{'bff-web'}
        Copy-Item -LiteralPath (Join-Path $projectRoot "runtime/tls/$other.cer") -Destination (Join-Path $wrongTrust "$name.cer")
        $wrong=[LocalTls]::CreateClient($wrongTrust)
        $wrong.Timeout=[TimeSpan]::FromSeconds(5)
        $rejected=$false
        try {$response=$wrong.GetAsync("https://localhost:$port/actuator/health").GetAwaiter().GetResult();$response.Dispose()} catch {$rejected=$true} finally {$wrong.Dispose()}
        ApiCheck "$name cliente rechaza certificado distinto" $rejected $true
        $cert=[Security.Cryptography.X509Certificates.X509Certificate2]::new((Join-Path $projectRoot "runtime/tls/$name.cer"))
        try {
            $sha=[Security.Cryptography.SHA256]::Create()
            try {$fingerprint=[BitConverter]::ToString($sha.ComputeHash($cert.RawData)).Replace('-','')} finally {$sha.Dispose()}
            $certificates.Add([pscustomobject]@{canal=$name;url="https://localhost:$port";sujeto=$cert.Subject;vence=$cert.NotAfter.ToUniversalTime().ToString('o');sha256=$fingerprint})
        } finally {$cert.Dispose()}
    }
    Write-Output "HTTPS aprobado: $($checks.Count) comprobaciones. Resultados en evidencias/https."
} finally {
    $checks | Export-Csv (Join-Path $evidence 'verificacion.csv') -NoTypeInformation -Encoding utf8
    $certificates | Export-Csv (Join-Path $evidence 'certificados.csv') -NoTypeInformation -Encoding utf8
    $http.Dispose()
    $resolved=(Resolve-Path -LiteralPath $wrongTrust).Path
    $allowed=(Join-Path $projectRoot 'runtime')+[IO.Path]::DirectorySeparatorChar
    if ($resolved.StartsWith($allowed,[StringComparison]::OrdinalIgnoreCase)) {Remove-Item -LiteralPath $resolved -Recurse -Force}
}
