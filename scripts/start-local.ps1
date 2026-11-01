param(
    [string]$DatabaseUrl = '',
    [string]$DatabaseUser = '',
    [string]$DatabasePassword = '',
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$runtimeDir = Join-Path $repoRoot '.local'
$portableTools = Join-Path (Split-Path $repoRoot -Parent) '.tools'
New-Item -ItemType Directory -Force -Path $runtimeDir | Out-Null

# Process-local environment only. Never change global Java or database settings.
if (-not (Get-Command java -ErrorAction SilentlyContinue)) {
    $jdk = Get-ChildItem (Join-Path $portableTools 'java') -Directory -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $jdk) { throw 'Java 21 is required. Install a JDK or set JAVA_HOME and PATH.' }
    $env:JAVA_HOME = $jdk.FullName
    $env:PATH = "$($jdk.FullName)\bin;$env:PATH"
}
$maven = (Get-Command mvn.cmd -ErrorAction SilentlyContinue).Source
if (-not $maven) {
    $maven = Join-Path $portableTools 'apache-maven-3.9.11\bin\mvn.cmd'
}

function Test-Endpoint([string]$Url) {
    try { return (Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200 }
    catch { return $false }
}

$pythonExe = Join-Path $repoRoot 'ai-service\.venv\Scripts\python.exe'
if (-not (Test-Path -LiteralPath $pythonExe)) {
    & python -m venv (Join-Path $repoRoot 'ai-service\.venv')
    if ($LASTEXITCODE -ne 0) { throw 'Could not create Python environment.' }
}
if (-not $SkipBuild -and -not (Test-Endpoint 'http://127.0.0.1:8000/health')) {
    & $pythonExe -m pip install -r (Join-Path $repoRoot 'ai-service\requirements-dev.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Python dependency installation failed.' }
}
# Values are captured in memory; never log them or include them in process arguments.
$envLoader = @'
import json, sys
from pathlib import Path
from dotenv import dotenv_values
allowed = {"DB_URL","DB_USERNAME","DB_PASSWORD","OPERATOR_PASSWORD","REVIEWER_PASSWORD","RULES_ADMIN_PASSWORD","AI_SERVICE_KEY","GEMINI_API_KEY","GEMINI_MODEL","GEMINI_TIMEOUT_MS","DOCUMENT_AUTOMATION_ENABLED","DOCUMENT_AUTOMATION_MAX_ATTEMPTS"}
values={}
root=Path(sys.argv[1])
for path in [root / ".env", root / "ai-service" / ".env"]:
    if path.exists():
        values.update({k:v for k,v in dotenv_values(path,encoding="utf-8-sig").items() if k in allowed and v is not None and v != ""})
print(json.dumps(values))
'@
$privateValues = $envLoader | & $pythonExe - $repoRoot | ConvertFrom-Json
if ($LASTEXITCODE -ne 0) { throw 'Private environment could not be loaded; install the updated AI requirements first.' }
foreach ($property in $privateValues.PSObject.Properties) {
    if (-not [Environment]::GetEnvironmentVariable($property.Name,'Process')) {
        [Environment]::SetEnvironmentVariable($property.Name,[string]$property.Value,'Process')
    }
}
if ($DatabaseUrl) { $env:DB_URL=$DatabaseUrl } elseif (-not $env:DB_URL) { $env:DB_URL='jdbc:postgresql://127.0.0.1:5432/afi_resolve' }
if ($DatabaseUser) { $env:DB_USERNAME=$DatabaseUser } elseif (-not $env:DB_USERNAME) { $env:DB_USERNAME='afi' }
if ($DatabasePassword) { $env:DB_PASSWORD=$DatabasePassword } elseif (-not $env:DB_PASSWORD) { $env:DB_PASSWORD='afi-local-demo' }
$env:SPRING_PROFILES_ACTIVE='demo'
$env:AI_SERVICE_URL='http://127.0.0.1:8000'
if (-not $env:AI_SERVICE_KEY) { $env:AI_SERVICE_KEY='afi-internal-demo-key' }

if (-not $SkipBuild -and -not (Test-Endpoint 'http://127.0.0.1:5173')) {
    Push-Location (Join-Path $repoRoot 'frontend')
    try {
        & npm.cmd ci
        if ($LASTEXITCODE -ne 0) { throw 'Node dependency installation failed.' }
        & npm.cmd run build
        if ($LASTEXITCODE -ne 0) { throw 'Frontend build failed.' }
    } finally { Pop-Location }
}
if (-not $SkipBuild -and -not (Test-Endpoint 'http://127.0.0.1:8080/actuator/health')) {
    & $maven -f (Join-Path $repoRoot 'backend\pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Backend build failed.' }
}
function Wait-ForEndpoint([string]$Url, [string]$Name) {
    $deadline = (Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        if (Test-Endpoint $Url) { Write-Output "$Name is healthy."; return }
        Start-Sleep -Milliseconds 500
    }
    $logs = @()
    foreach ($kind in @('stderr', 'stdout')) {
        $file = Join-Path $runtimeDir "$Name.$kind.log"
        if (Test-Path -LiteralPath $file) { $logs += (Get-Content -LiteralPath $file -Tail 15) }
    }
    throw "$Name did not become healthy within 60 seconds.`n$($logs -join "`n")"
}
$started = @()
function Start-OwnedProcess([string]$Name, [string]$Executable, [string]$Arguments, [string]$WorkingDirectory) {
    $process = Start-Process -FilePath $Executable -ArgumentList $Arguments -WorkingDirectory $WorkingDirectory -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtimeDir "$Name.stdout.log") -RedirectStandardError (Join-Path $runtimeDir "$Name.stderr.log")
    $script:started += @{ name = $Name; pid = $process.Id; startedAt = $process.StartTime.ToUniversalTime().ToString('o') }
}

if (-not (Test-Endpoint 'http://127.0.0.1:8000/health')) {
    Start-OwnedProcess 'ai-service' $pythonExe '-m uvicorn app.main:app --host 127.0.0.1 --port 8000' (Join-Path $repoRoot 'ai-service')
}
if (-not (Test-Endpoint 'http://127.0.0.1:8080/actuator/health')) {
    $jar = Get-ChildItem (Join-Path $repoRoot 'backend\target') -Filter '*.jar' | Select-Object -First 1
    if (-not $jar) { throw 'Build the backend before starting with -SkipBuild.' }
    Start-OwnedProcess 'backend' (Get-Command java.exe).Source ('-jar "' + $jar.FullName + '"') (Join-Path $repoRoot 'backend')
}
if (-not (Test-Endpoint 'http://127.0.0.1:5173')) {
    $vite = Join-Path $repoRoot 'frontend\node_modules\vite\bin\vite.js'
    Start-OwnedProcess 'frontend' (Get-Command node.exe).Source ('"' + $vite + '" --host 127.0.0.1 --port 5173 --strictPort') (Join-Path $repoRoot 'frontend')
}
$processFile = Join-Path $runtimeDir 'processes.json'
$prior = @()
if (Test-Path -LiteralPath $processFile) { $prior = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json }
ConvertTo-Json -InputObject @($prior + $started) | Set-Content -LiteralPath $processFile
Wait-ForEndpoint 'http://127.0.0.1:8000/health' 'ai-service'
Wait-ForEndpoint 'http://127.0.0.1:8080/actuator/health' 'backend'
Wait-ForEndpoint 'http://127.0.0.1:5173' 'frontend'
Write-Output 'Local application is ready. Open http://localhost:5173.'
Write-Output "Logs: $runtimeDir"
Write-Output 'Stop only these application processes with scripts/stop-local.ps1. PostgreSQL is managed separately.'
