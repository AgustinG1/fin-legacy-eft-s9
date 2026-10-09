$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'entorno-servicios.ps1')
Push-Location $projectRoot
try {
    $evidence=Join-Path $projectRoot 'evidencias/semana3'
    New-Item -ItemType Directory -Force -Path $evidence,'logs','runtime' | Out-Null
    $values=Read-EftEnvironment
    $runId=[guid]::NewGuid().ToString('N')
    $times=@()
    foreach($workers in @(1,3)) {
        $watch=[Diagnostics.Stopwatch]::StartNew()
        & java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo --eft.input-directory=data/semana_3 "--eft.workers=$workers" "--spring.datasource.url=jdbc:h2:file:./runtime/semana3-$runId-$workers;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE" "--eft.output-directory=output/semana3-$workers" *> "logs/semana3-$workers.log"
        if($LASTEXITCODE -ne 0){throw "Fallo Batch con $workers trabajadores"}
        $watch.Stop()
        $times += [pscustomobject]@{trabajadores=$workers;filasEntrada=3000;milisegundos=[math]::Round($watch.Elapsed.TotalMilliseconds,2)}
    }
    $env:DB_PASSWORD=$values.DB_PASSWORD
    $env:EFT_INTEREST_RATE=$values.EFT_INTEREST_RATE
    $env:EFT_INTEREST_PERIOD=$values.EFT_INTEREST_PERIOD
    & java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=postgres --eft.input-directory=data/semana_3 --eft.output-directory=output/semana3-postgres "--eft.replay=semana3-$runId" *> logs/semana3-postgres.log
    if($LASTEXITCODE -ne 0){throw 'Fallo Batch semana 3 en PostgreSQL'}
    $compared=@()
    foreach($file in Get-ChildItem output/semana3-1 -Recurse -File -Filter '*.csv') {
        $relative=[IO.Path]::GetRelativePath((Join-Path $projectRoot 'output/semana3-1'),$file.FullName)
        $one=[IO.File]::ReadAllText($file.FullName).Replace("`r`n","`n")
        $three=[IO.File]::ReadAllText((Join-Path 'output/semana3-3' $relative)).Replace("`r`n","`n")
        $pg=[IO.File]::ReadAllText((Join-Path 'output/semana3-postgres' $relative)).Replace("`r`n","`n")
        if($one -cne $three -or $one -cne $pg){throw "Diferencia de resultados: $relative"}
        $compared += [pscustomobject]@{archivo=$relative;secuencialIgualParalelo=$true;h2IgualPostgres=$true}
    }
    $times | Export-Csv (Join-Path $evidence 'tiempos.csv') -NoTypeInformation -Encoding utf8
    $compared | Export-Csv (Join-Path $evidence 'equivalencia.csv') -NoTypeInformation -Encoding utf8
    foreach($file in Get-ChildItem output/semana3-postgres -Recurse -Filter control.csv) {
        Copy-Item -LiteralPath $file.FullName -Destination (Join-Path $evidence ($file.Directory.Parent.Name+'-control.csv'))
    }
    & $dockerPath compose exec -T db psql -X -U eft -d eft -c "SELECT e.job_execution_id,i.job_name,e.status,s.step_name,s.read_count,s.write_count FROM batch_job_execution e JOIN batch_job_instance i USING(job_instance_id) JOIN batch_step_execution s USING(job_execution_id) WHERE e.job_execution_id IN (SELECT job_execution_id FROM batch_job_execution_params WHERE parameter_name='replay' AND parameter_value='semana3-$runId') ORDER BY e.job_execution_id,s.step_name" *> (Join-Path $evidence 'particiones-postgres.txt')
    if($LASTEXITCODE -ne 0){throw 'No se pudieron guardar particiones'}
    $times | Format-Table
    Write-Output "Equivalencia aprobada en $($compared.Count) archivos CSV. Los tiempos incluyen arranque; no prueban aceleracion en produccion."
} finally {
    Remove-Item Env:DB_PASSWORD,Env:EFT_INTEREST_RATE,Env:EFT_INTEREST_PERIOD -ErrorAction SilentlyContinue
    Pop-Location
}
