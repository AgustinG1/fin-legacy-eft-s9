$projectRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$dockerCommand = Get-Command docker -ErrorAction SilentlyContinue
if ($dockerCommand) { $dockerPath = $dockerCommand.Source }
else { $dockerPath = Join-Path $env:LOCALAPPDATA 'Programs/DockerDesktop/resources/bin/docker.exe' }
if (-not (Test-Path -LiteralPath $dockerPath)) { throw 'Abre Docker Desktop y comprueba su instalacion.' }
$env:PATH = (Split-Path $dockerPath) + [IO.Path]::PathSeparator + $env:PATH
$composeFiles = @('compose','-f',(Join-Path $projectRoot 'compose.yaml'),'-f',(Join-Path $projectRoot 'compose.servicios.yaml'))
function Read-EftEnvironment {
    $values = @{}
    foreach ($line in Get-Content -LiteralPath (Join-Path $projectRoot '.env')) {
        if ($line -match '^([A-Z][A-Z0-9_]*)=(.*)$') { $values[$Matches[1]] = $Matches[2] }
    }
    return $values
}
function Invoke-EftCompose {
    & $dockerPath @composeFiles @args
    if ($LASTEXITCODE -ne 0) { throw 'Docker Compose fallo; revisa el mensaje anterior.' }
}
