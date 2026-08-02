[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path

Push-Location $repoRoot
try {
    & ".\mvnw.cmd" `
        -q `
        "-Dtest=AeronReplayCoordinatorIntegrationTest#hardCrashResumesFromLastPersistedAeronPosition" `
        test

    if ($LASTEXITCODE -ne 0) {
        throw "Replay demonstration failed with Maven exit code $LASTEXITCODE"
    }

    Write-Host ""
    Write-Host "REPLAY WORKFLOW: PASS"
}
finally {
    Pop-Location
}
