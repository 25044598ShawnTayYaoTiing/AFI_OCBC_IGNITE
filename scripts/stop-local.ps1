$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$processFile = Join-Path $repoRoot '.local\processes.json'
if (-not (Test-Path -LiteralPath $processFile)) { Write-Output 'No recorded application processes.'; return }
$entries = Get-Content -LiteralPath $processFile -Raw | ConvertFrom-Json
function Stop-OwnedTree([int]$ProcessId) {
    # Python's Windows venv launcher may own the actual uvicorn child process.
    $children = Get-CimInstance Win32_Process -Filter "ParentProcessId=$ProcessId" -ErrorAction SilentlyContinue
    foreach ($child in $children) { Stop-OwnedTree ([int]$child.ProcessId) }
    Stop-Process -Id $ProcessId -ErrorAction SilentlyContinue
}
foreach ($entry in $entries) {
    $process = Get-Process -Id ([int]$entry.pid) -ErrorAction SilentlyContinue
    if (-not $process -or -not $process.StartTime) { continue }
    # PID reuse must not cause us to stop an unrelated process.
    $startedAt = [DateTimeOffset]::Parse([string]$entry.startedAt).UtcDateTime.ToString('o')
    $actualStart = $process.StartTime.ToUniversalTime().ToString('o')
    if ($process -and $actualStart -eq $startedAt) {
        Stop-OwnedTree ([int]$entry.pid)
        Write-Output "Stopped $($entry.name)."
    }
}
Remove-Item -LiteralPath $processFile
