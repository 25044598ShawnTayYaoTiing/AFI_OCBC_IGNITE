param([switch]$AllowFallback, [switch]$Browser)
$ErrorActionPreference = 'Stop'
$repo = (Resolve-Path (Join-Path $PSScriptRoot '../..')).Path
$tools = Join-Path (Split-Path $repo -Parent) '.tools'
$runtime = Join-Path $repo ('.local/service-e2e-' + [guid]::NewGuid().ToString('N'))
$report = Join-Path $repo '.local/service-request-live-e2e.json'
New-Item -ItemType Directory -Force -Path $runtime | Out-Null
if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    @{ passed=$false; status='BLOCKED'; scope='Full shared-stack test'; reason='Docker CLI/Engine unavailable; no disposable PostgreSQL was started'; databaseTested=$false } |
        ConvertTo-Json | Set-Content -LiteralPath $report
    throw 'Full end-to-end test blocked: install/start Docker with Compose, then rerun. No permanent database or AWS resources were used.'
}
& docker info --format '{{.ServerVersion}}' *> (Join-Path $runtime 'docker-info.log')
if ($LASTEXITCODE -ne 0) { throw 'Docker Engine is unavailable. See the private local Docker log.' }
foreach ($port in @(55433,18000,18080,15173,15174,11025,18025)) {
    if (Get-NetTCPConnection -State Listen -LocalPort $port -ErrorAction SilentlyContinue) { throw "Integration port $port is occupied. Existing services were not stopped." }
}
$python = Join-Path $repo 'ai-service/.venv/Scripts/python.exe'
# Capture only approved settings in memory; never render a private environment file.
$loader = @'
import json,sys
from pathlib import Path
from dotenv import dotenv_values
allowed={'REVIEWER_PASSWORD','RULES_ADMIN_PASSWORD','AI_SERVICE_KEY','GEMINI_API_KEY','GEMINI_MODEL','GEMINI_TIMEOUT_MS'}
values={}
for p in [Path(sys.argv[1])/'.env',Path(sys.argv[1])/'ai-service/.env']:
 if p.exists(): values.update({k:v for k,v in dotenv_values(p,encoding='utf-8-sig').items() if k in allowed and v})
print(json.dumps(values))
'@
$private = $loader | & $python - $repo | ConvertFrom-Json
foreach ($property in $private.PSObject.Properties) {
    if (-not [Environment]::GetEnvironmentVariable($property.Name,'Process')) { [Environment]::SetEnvironmentVariable($property.Name,[string]$property.Value,'Process') }
}
$bytes=New-Object byte[] 32; $rng=[Security.Cryptography.RandomNumberGenerator]::Create(); $rng.GetBytes($bytes); $rng.Dispose()
$env:TEST_DB_PASSWORD = [Convert]::ToBase64String($bytes)
$env:TEST_DB_PORT = '55433'
$env:DB_URL = 'jdbc:postgresql://127.0.0.1:55433/afi_resolve_test'
$env:DB_USERNAME = 'afi_test'; $env:DB_PASSWORD = $env:TEST_DB_PASSWORD
$env:PORT = '18080'; $env:AI_SERVICE_URL = 'http://127.0.0.1:18000'
$env:SPRING_PROFILES_ACTIVE = 'demo'; $env:SMTP_ENABLED = 'true'; $env:SMTP_HOST='127.0.0.1'; $env:SMTP_PORT='11025'; $env:SMTP_USERNAME=''; $env:SMTP_PASSWORD=''; $env:SMTP_FROM='afi-synthetic@example.test'; $env:SMTP_STARTTLS='false'; $env:SYNTHETIC_CUSTOMERS_ENABLED = 'false'
$env:DOCUMENT_STORAGE_PROVIDER = 'local'; $env:DOCUMENT_STORAGE = Join-Path $runtime 'documents'
$env:CUSTOMER_PORTAL_ORIGINS = 'http://127.0.0.1:15174'
$env:FRONTEND_ORIGIN = 'http://127.0.0.1:15173'
$env:VITE_API_BASE_URL = 'http://127.0.0.1:18080/api/customer'
$env:VITE_BACKEND_TARGET = 'http://127.0.0.1:18080'; $env:VITE_AI_TARGET='http://127.0.0.1:18000'
if (-not $env:AI_SERVICE_KEY) { $env:AI_SERVICE_KEY='afi-internal-demo-key' }
if (-not $env:REVIEWER_PASSWORD) { $env:REVIEWER_PASSWORD='reviewer-demo' }
if (-not $env:RULES_ADMIN_PASSWORD) { $env:RULES_ADMIN_PASSWORD='rules-admin-demo' }
$env:JAVA_HOME = (Get-ChildItem (Join-Path $tools 'java') -Directory | Select-Object -First 1).FullName
$java = Join-Path $env:JAVA_HOME 'bin/java.exe'; $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
$project = 'afi-service-e2e-' + [guid]::NewGuid().ToString('N').Substring(0,10)
$compose = @('compose','--project-name',$project,'--env-file',(Join-Path $repo '.env.example'),'-f',(Join-Path $repo 'docker-compose.integration.yml'))
$owned = @(); $startedDatabase=$false; $outcome=1
function Wait-Healthy([string]$Url) {
    $deadline=(Get-Date).AddSeconds(60)
    while ((Get-Date) -lt $deadline) {
        try { if ((Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { return } } catch {}
        Start-Sleep -Milliseconds 500
    }
    throw "Local test service did not become healthy: $Url"
}
try {
    $startedDatabase=$true
    & docker @compose up -d --wait --wait-timeout 60 postgres
    if ($LASTEXITCODE -ne 0) { throw 'Disposable PostgreSQL did not become healthy.' }
    & (Join-Path $tools 'apache-maven-3.9.11/bin/mvn.cmd') -q -f (Join-Path $repo 'backend/pom.xml') -DskipTests package
    if ($LASTEXITCODE -ne 0) { throw 'Backend packaging failed.' }
    $sink=Join-Path $repo 'scripts/demo/local_smtp_sink.py'
    $owned += Start-Process -FilePath $python -ArgumentList ('"'+$sink+'"') -WorkingDirectory $repo -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'smtp.stdout.log') -RedirectStandardError (Join-Path $runtime 'smtp.stderr.log')
    Wait-Healthy 'http://127.0.0.1:18025/health'
    $owned += Start-Process -FilePath $python -ArgumentList '-m uvicorn app.main:app --host 127.0.0.1 --port 18000' -WorkingDirectory (Join-Path $repo 'ai-service') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'ai.stdout.log') -RedirectStandardError (Join-Path $runtime 'ai.stderr.log')
    $jar=Join-Path $repo 'backend/target/resolve-0.1.0.jar'
    $owned += Start-Process -FilePath $java -ArgumentList @('-jar',('"'+$jar+'"')) -WorkingDirectory (Join-Path $repo 'backend') -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime 'backend.stdout.log') -RedirectStandardError (Join-Path $runtime 'backend.stderr.log')
    Wait-Healthy 'http://127.0.0.1:18000/health'; Wait-Healthy 'http://127.0.0.1:18080/actuator/health'
    $arguments=@((Join-Path $repo 'scripts/demo/service_request_e2e.py'),'--confirm-disposable','--local-mailbox','http://127.0.0.1:18025','--report',$report)
    if ($AllowFallback) { $arguments += '--allow-fallback' }
    if ($Browser) { $arguments += @('--browser-state',(Join-Path $runtime 'browser-state.json')) }
    & $python @arguments
    $outcome=$LASTEXITCODE
    if ($Browser -and $outcome -eq 0) {
        $portal=Join-Path (Split-Path $repo -Parent) 'customer-portal'
        $node=(Get-Command node.exe).Source
        foreach ($frontend in @(@{path=(Join-Path $repo 'frontend');port=15173;name='rm'},@{path=$portal;port=15174;name='portal'})) {
            $vite=Join-Path $frontend.path 'node_modules/vite/bin/vite.js'
            $owned += Start-Process -FilePath $node -ArgumentList @(('"'+$vite+'"'),'--host','127.0.0.1','--port',[string]$frontend.port,'--strictPort') -WorkingDirectory $frontend.path -WindowStyle Hidden -PassThru -RedirectStandardOutput (Join-Path $runtime ($frontend.name+'.stdout.log')) -RedirectStandardError (Join-Path $runtime ($frontend.name+'.stderr.log'))
            Wait-Healthy ('http://127.0.0.1:'+$frontend.port)
        }
        $env:AFI_SERVICE_BROWSER_STATE=Join-Path $runtime 'browser-state.json'
        & $node (Join-Path $portal 'scripts/service-request-browser-check.cjs')
        $outcome=$LASTEXITCODE
    }
} finally {
    foreach ($process in $owned) { if (-not $process.HasExited) { Stop-Process -Id $process.Id -ErrorAction SilentlyContinue } }
    if ($startedDatabase) { & docker @compose down --volumes }
}
exit $outcome
