$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
$values=Read-EftEnvironment
if (-not $values.ContainsKey('BFF_TLS_PASSWORD')) {
    $values.BFF_TLS_PASSWORD=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
    Add-Content -LiteralPath (Join-Path $projectRoot '.env') -Value "BFF_TLS_PASSWORD=$($values.BFF_TLS_PASSWORD)" -Encoding utf8
}
$tls=Join-Path $projectRoot 'runtime/tls'
New-Item -ItemType Directory -Force -Path $tls | Out-Null
$previousPassword=$env:EFT_TLS_PASSWORD
try {
    $env:EFT_TLS_PASSWORD=$values.BFF_TLS_PASSWORD
    foreach ($service in @('bff-web','bff-movil','bff-cajero')) {
        $container=@('run','--rm','--mount',"type=bind,source=$tls,target=/tls",'--env','EFT_TLS_PASSWORD','eclipse-temurin:17-jre','keytool')
        if (-not (Test-Path -LiteralPath (Join-Path $tls "$service.p12"))) {
            & $dockerPath @container -genkeypair -alias $service -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -storetype PKCS12 -keystore "/tls/$service.p12" -storepass:env EFT_TLS_PASSWORD -keypass:env EFT_TLS_PASSWORD -dname "CN=localhost,OU=EFT S9,O=Laboratorio" -ext "SAN=dns:localhost,ip:127.0.0.1,dns:$service" -ext 'EKU=serverAuth' -validity 365 -noprompt
            if ($LASTEXITCODE -ne 0) {throw "No se pudo generar el certificado de $service."}
        }
        & $dockerPath @container -exportcert -alias $service -keystore "/tls/$service.p12" -storepass:env EFT_TLS_PASSWORD -file "/tls/$service.cer"
        if ($LASTEXITCODE -ne 0) {throw "No se pudo exportar el certificado de $service."}
    }
    Write-Output 'Certificados HTTPS locales preparados. Claves y certificados permanecen en runtime/tls.'
} finally {$env:EFT_TLS_PASSWORD=$previousPassword}
