param([switch]$SinConstruir)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
Push-Location $projectRoot
try {
    & (Join-Path $PSScriptRoot 'iniciar-eventos.ps1') -SinConstruir:$SinConstruir
    $values=Read-EftEnvironment
    if(-not $values.ContainsKey('REPORTES_DB_PASSWORD')) {
        $values.REPORTES_DB_PASSWORD=[guid]::NewGuid().ToString('N')+[guid]::NewGuid().ToString('N')
        Add-Content '.env' "REPORTES_DB_PASSWORD=$($values.REPORTES_DB_PASSWORD)" -Encoding utf8
    }
    $composeFiles += @('-f',(Join-Path $projectRoot 'compose.canales.yaml'),'-f',(Join-Path $projectRoot 'compose.kafka.yaml'),'-f',(Join-Path $projectRoot 'compose.reportes.yaml'))
    if(-not $SinConstruir) {Invoke-EftCompose --profile batch build batch}
    Invoke-EftCompose --profile batch run --rm -T batch
    $passwordLiteral=$values.REPORTES_DB_PASSWORD.Replace("'","''");$passwordCommand=$passwordLiteral.Replace("'","''")
    $sql=@"
SELECT 'CREATE ROLE eft_reportes LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE PASSWORD ''$passwordCommand''' WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='eft_reportes')
\gexec
ALTER ROLE eft_reportes PASSWORD '$passwordLiteral';
GRANT CONNECT ON DATABASE eft TO eft_reportes;
GRANT USAGE ON SCHEMA public TO eft_reportes;
GRANT SELECT ON legacy_row,batch_job_instance,batch_job_execution,batch_job_execution_params TO eft_reportes;
"@
    $sql | & $dockerPath @composeFiles exec -T db psql -X -U eft -d eft -v ON_ERROR_STOP=1 *> logs/init-reportes.log
    if($LASTEXITCODE -ne 0) {throw 'No se pudieron preparar los permisos de reportes.'}
    if(-not $SinConstruir) {Invoke-EftCompose build reportes}
    Invoke-EftCompose up -d --wait --wait-timeout 240 reportes
    Write-Output 'Reportes Batch disponible en 8105 y consulta administrativa web en 8201.'
} finally {Pop-Location}
