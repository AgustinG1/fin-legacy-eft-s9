param([switch]$SinConstruir)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'iniciar-canales.ps1') -SinConstruir:$SinConstruir
    $values=Read-EftEnvironment
    if (-not $values.ContainsKey('AUDITORIA_DB_PASSWORD')) {
        $values.AUDITORIA_DB_PASSWORD=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
        Add-Content '.env' "AUDITORIA_DB_PASSWORD=$($values.AUDITORIA_DB_PASSWORD)" -Encoding utf8
    }
    $composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'),'-f',(Join-Path $projectRoot 'compose.kafka.yaml'))
    $passwordLiteral=$values.AUDITORIA_DB_PASSWORD.Replace("'","''")
    $passwordCommand=$passwordLiteral.Replace("'","''")
    $sql=@"
SELECT 'CREATE ROLE eft_auditoria LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ''$passwordCommand''' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='eft_auditoria')
\gexec
ALTER ROLE eft_auditoria PASSWORD '$passwordLiteral';
SELECT 'CREATE DATABASE eft_auditoria OWNER eft_auditoria' WHERE NOT EXISTS (SELECT 1 FROM pg_database WHERE datname='eft_auditoria')
\gexec
REVOKE CONNECT ON DATABASE eft_auditoria FROM PUBLIC;
GRANT CONNECT ON DATABASE eft_auditoria TO eft_auditoria;
"@
    $sql | & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft -v ON_ERROR_STOP=1 *> logs/init-auditoria.log
    if ($LASTEXITCODE -ne 0) {throw 'No se pudo preparar la base de auditoria; revisa el log local.'}
    Invoke-EftCompose up -d --wait --wait-timeout 180 kafka
    foreach ($topic in @('eft.pagos.v1','eft.pagos.v1.DLT')) {
        Invoke-EftCompose exec -T -e 'KAFKA_HEAP_OPTS=-Xms32m -Xmx96m' kafka /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --create --if-not-exists --topic $topic --partitions 3 --replication-factor 1
    }
    if (-not $SinConstruir) {Invoke-EftCompose build auditoria}
    Invoke-EftCompose up -d --wait --wait-timeout 240 pagos auditoria
    Write-Output 'Kafka y auditoria iniciados. Auditoria local: 8104. Kafka solo accesible en la red interna de Docker.'
} finally {Pop-Location}
