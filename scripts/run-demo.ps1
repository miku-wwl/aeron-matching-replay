[CmdletBinding()]
param(
    [int]$OrderCount = 5000,
    [long]$Seed = 20210801,
    [long]$CrashAfterSequence = 4000,
    [string]$ConsumerName = "asset-projection",
    [int]$TimeoutSeconds = 180
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "runtime"))
$logs = Join-Path $runtimeRoot "logs"
$env:MAVEN_OPTS = "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.util.zip=ALL-UNNAMED"
$jvmArgs = @(
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED",
    "-Dreplay.runtime.dir=$runtimeRoot"
)
$archiveProcess = $null
$consumerProcess = $null
$engineProcess = $null
$replayProcess = $null

function Wait-LogMarker {
    param(
        [string]$Path,
        [string]$Marker,
        [System.Diagnostics.Process]$Process,
        [int]$Timeout
    )

    $deadline = [DateTime]::UtcNow.AddSeconds($Timeout)
    while ([DateTime]::UtcNow -lt $deadline) {
        if ((Test-Path -LiteralPath $Path) -and
            (Select-String -LiteralPath $Path -SimpleMatch $Marker -Quiet)) {
            return
        }
        if ($null -ne $Process -and $Process.HasExited) {
            throw "Process $($Process.Id) exited before marker '$Marker' appeared in $Path (exit $($Process.ExitCode))"
        }
        Start-Sleep -Milliseconds 100
    }
    throw "Timed out waiting for marker '$Marker' in $Path"
}

function Show-LogTail {
    param([string]$Path)
    if (Test-Path -LiteralPath $Path) {
        Write-Host "----- $Path -----"
        Get-Content -LiteralPath $Path -Tail 80
    }
}

function Stop-ProcessTree {
    param([System.Diagnostics.Process]$Process)
    if ($null -ne $Process -and -not $Process.HasExited) {
        & taskkill.exe /PID $Process.Id /T /F 2>$null | Out-Null
    }
}

Push-Location $repoRoot
try {
    & ".\scripts\clean-data.ps1"
    & ".\scripts\build.ps1"

    $archiveLog = Join-Path $logs "archive.log"
    $archiveErr = Join-Path $logs "archive.err.log"
    $archiveJar = Join-Path $repoRoot "aeron-infrastructure\target\aeron-infrastructure-1.0.0-SNAPSHOT-all.jar"
    $archiveArgs = $jvmArgs + @("-jar", $archiveJar)
    $archiveProcess = Start-Process -FilePath "java.exe" -ArgumentList $archiveArgs `
        -RedirectStandardOutput $archiveLog -RedirectStandardError $archiveErr -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeRoot "pids\archive.pid") -Value $archiveProcess.Id
    Wait-LogMarker -Path $archiveLog -Marker "ARCHIVE_READY" -Process $archiveProcess -Timeout 30

    $consumerLog = Join-Path $logs "consumer-live.log"
    $consumerErr = Join-Path $logs "consumer-live.err.log"
    $consumerJar = Join-Path $repoRoot "projection-consumer-app\target\projection-consumer-app-1.0.0-SNAPSHOT-all.jar"
    $consumerArgs = $jvmArgs + @(
        "-jar", $consumerJar,
        "--mode=live",
        "--crashAfterSequence=$CrashAfterSequence",
        "--checkpointEvery=1",
        "--consumerName=$ConsumerName",
        "--timeoutSeconds=$TimeoutSeconds"
    )
    $consumerProcess = Start-Process -FilePath "java.exe" -ArgumentList $consumerArgs `
        -RedirectStandardOutput $consumerLog -RedirectStandardError $consumerErr -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeRoot "pids\consumer.pid") -Value $consumerProcess.Id
    Wait-LogMarker -Path $consumerLog -Marker "CONSUMER_READY" -Process $consumerProcess -Timeout 30

    $engineLog = Join-Path $logs "engine.log"
    $engineErr = Join-Path $logs "engine.err.log"
    $engineJar = Join-Path $repoRoot "matching-engine-app\target\matching-engine-app-1.0.0-SNAPSHOT-all.jar"
    $engineArgs = $jvmArgs + @(
        "-jar", $engineJar,
        "--orderCount=$OrderCount",
        "--seed=$Seed",
        "--symbolId=1",
        "--publishDelayMicros=0"
    )
    $engineProcess = Start-Process -FilePath "java.exe" -ArgumentList $engineArgs `
        -RedirectStandardOutput $engineLog -RedirectStandardError $engineErr -PassThru
    Set-Content -LiteralPath (Join-Path $runtimeRoot "pids\engine.pid") -Value $engineProcess.Id

    if (-not $consumerProcess.WaitForExit($TimeoutSeconds * 1000)) {
        throw "Consumer did not crash within timeout"
    }
    if ($consumerProcess.ExitCode -ne 77) {
        throw "Consumer exit code was $($consumerProcess.ExitCode), expected 77"
    }
    Wait-LogMarker -Path $consumerLog -Marker "SIMULATED_CRASH" -Process $consumerProcess -Timeout 5

    if (-not $engineProcess.WaitForExit($TimeoutSeconds * 1000)) {
        throw "Matching engine did not finish within timeout"
    }
    if ($engineProcess.ExitCode -ne 0) {
        throw "Matching engine failed with exit code $($engineProcess.ExitCode)"
    }
    Wait-LogMarker -Path $engineLog -Marker "ENGINE_FINISHED" -Process $engineProcess -Timeout 5

    $replayLog = Join-Path $logs "replay.log"
    $replayErr = Join-Path $logs "replay.err.log"
    $replayJar = Join-Path $repoRoot "replay-coordinator-app\target\replay-coordinator-app-1.0.0-SNAPSHOT-all.jar"
    $replayArgs = $jvmArgs + @(
        "-jar", $replayJar,
        "--consumerName=$ConsumerName",
        "--followLive=false",
        "--timeoutSeconds=$TimeoutSeconds"
    )
    $replayProcess = Start-Process -FilePath "java.exe" -ArgumentList $replayArgs `
        -RedirectStandardOutput $replayLog -RedirectStandardError $replayErr -PassThru
    if (-not $replayProcess.WaitForExit($TimeoutSeconds * 1000)) {
        throw "Replay coordinator did not finish within timeout"
    }
    if ($replayProcess.ExitCode -ne 0) {
        throw "Replay coordinator failed with exit code $($replayProcess.ExitCode)"
    }
    Wait-LogMarker -Path $replayLog -Marker "status=PASS" -Process $replayProcess -Timeout 5

    Write-Host "DEMO_PASS"
    Write-Host "archiveLog=$archiveLog"
    Write-Host "consumerLog=$consumerLog"
    Write-Host "engineLog=$engineLog"
    Write-Host "replayLog=$replayLog"
    Write-Host "checkpoint=$(Join-Path $runtimeRoot "checkpoints\$ConsumerName.checkpoint")"
    Write-Host "manifest=$(Join-Path $runtimeRoot "manifests\current-run.properties")"
}
catch {
    Write-Host "DEMO_FAILED: $_" -ForegroundColor Red
    Show-LogTail (Join-Path $logs "archive.log")
    Show-LogTail (Join-Path $logs "archive.err.log")
    Show-LogTail (Join-Path $logs "consumer-live.log")
    Show-LogTail (Join-Path $logs "consumer-live.err.log")
    Show-LogTail (Join-Path $logs "engine.log")
    Show-LogTail (Join-Path $logs "engine.err.log")
    Show-LogTail (Join-Path $logs "replay.log")
    Show-LogTail (Join-Path $logs "replay.err.log")
    exit 1
}
finally {
    Stop-ProcessTree $replayProcess
    Stop-ProcessTree $engineProcess
    Stop-ProcessTree $consumerProcess
    Stop-ProcessTree $archiveProcess
    Pop-Location
}
