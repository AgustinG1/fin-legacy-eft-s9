param([ValidateRange(1,10)][int]$Repeticiones = 3)
$ErrorActionPreference = 'Stop'
$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
Push-Location $projectRoot
try {
    New-Item -ItemType Directory -Force -Path 'logs','runtime' | Out-Null
    $runId = [guid]::NewGuid().ToString('N')
    $inputRows = (Get-ChildItem -LiteralPath data/semana_3 -Filter '*.csv' | ForEach-Object { @(Import-Csv -LiteralPath $_.FullName).Count } | Measure-Object -Sum).Sum
    $results = @()
    for ($iteration = 1; $iteration -le $Repeticiones; $iteration++) {
        $workerOrder = if ($iteration % 2 -eq 0) { @(3,1) } else { @(1,3) }
        foreach ($workers in $workerOrder) {
            $label = "$runId-$iteration-$workers"
            $watch = [Diagnostics.Stopwatch]::StartNew()
            & java -jar batch/target/batch-0.1.0-SNAPSHOT.jar --spring.profiles.active=demo "--eft.workers=$workers" "--spring.datasource.url=jdbc:h2:file:./runtime/medicion-$label;MODE=PostgreSQL;DB_CLOSE_ON_EXIT=FALSE" "--eft.output-directory=output/medicion-$label" *> "logs/medicion-$label.log"
            $code = $LASTEXITCODE
            $watch.Stop()
            if ($code -ne 0) { throw "Fallo la medicion $label. Revisa el log." }
            $results += [pscustomobject]@{Repeticion=$iteration;Trabajadores=$workers;Milisegundos=[math]::Round($watch.Elapsed.TotalMilliseconds,2);RegistrosEntrada=$inputRows;Log="logs/medicion-$label.log"}
        }
    }
    $results | Export-Csv -LiteralPath "logs/rendimiento-$runId.csv" -NoTypeInformation -Encoding utf8
    $results | Format-Table
    Write-Output 'Los tiempos incluyen inicio, carga y reportes; usa medianas y no supongas una mejora con conjuntos pequenos.'
} finally { Pop-Location }
