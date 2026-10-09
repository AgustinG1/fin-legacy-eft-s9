param([switch]$SinConstruir)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path logs | Out-Null
    $envFile = Join-Path $projectRoot '.env'
    if (-not (Test-Path -LiteralPath $envFile)) {
        @('EFT_INTEREST_RATE=0.05','EFT_INTEREST_PERIOD=TRIMESTRAL-DEMO') | Set-Content -LiteralPath $envFile -Encoding utf8
    }
    $values = Read-EftEnvironment
    $required = @('DB_PASSWORD','CLIENTES_DB_PASSWORD','CUENTAS_DB_PASSWORD','PAGOS_DB_PASSWORD',
                  'OPERATOR_SECRET','CONSULTA_SECRET','CUENTAS_INTERNAL_SECRET','PAGOS_INTERNAL_SECRET')
    foreach ($name in $required) {
        if (-not $values.ContainsKey($name)) {
            $secret = [guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N')
            Add-Content -LiteralPath $envFile -Value "$name=$secret" -Encoding utf8
            $values[$name] = $secret
        }
        if ($values[$name].Length -lt 24) { throw "El valor local de $name debe tener al menos 24 caracteres." }
        [Environment]::SetEnvironmentVariable($name,$values[$name],'Process')
    }
    Invoke-EftCompose config --quiet
    Invoke-EftCompose up -d --wait db
    # Las contrasenas se envian por stdin para no exponerlas en argumentos o archivos SQL.
    foreach ($service in @('clientes','cuentas','pagos')) {
        $role = "eft_$service"
        $secretName = $service.ToUpperInvariant() + '_DB_PASSWORD'
        $passwordLiteral = $values[$secretName].Replace("'","''")
        $passwordInCommand = $passwordLiteral.Replace("'","''")
        $sql = @"
SELECT 'CREATE ROLE $role LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ''$passwordInCommand''' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='$role')
\gexec
ALTER ROLE $role PASSWORD '$passwordLiteral';
SELECT 'CREATE DATABASE $role OWNER $role' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='$role')
\gexec
REVOKE CONNECT ON DATABASE $role FROM PUBLIC;
GRANT CONNECT ON DATABASE $role TO $role;
"@
        $sql | & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft -v ON_ERROR_STOP=1 *> "logs/init-$service.log"
        if ($LASTEXITCODE -ne 0) { throw "No se pudo preparar la base de $service. Revisa logs/init-$service.log localmente." }
    }
    if (-not $SinConstruir) { Invoke-EftCompose build auth-server clientes cuentas pagos }
    Invoke-EftCompose up -d --wait --wait-timeout 240 auth-server clientes cuentas pagos
    Write-Output 'Servicios iniciados: OAuth2 9100, clientes 8101, cuentas 8102, pagos 8103. Credenciales locales en .env.'
} finally { Pop-Location }
