[CmdletBinding()]
param(
    [long]$CrashAfterSequence = 4000,
    [int]$CheckpointEvery = 1,
    [string]$ConsumerName = "asset-projection"
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "runtime"))
$jvmArgs = @(
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED"
)
$jar = Join-Path $repoRoot "projection-consumer-app\target\projection-consumer-app-1.0.0-SNAPSHOT-all.jar"

Push-Location $repoRoot
try {
    & java @jvmArgs "-Dreplay.runtime.dir=$runtimeRoot" -jar $jar `
        "--mode=live" "--crashAfterSequence=$CrashAfterSequence" `
        "--checkpointEvery=$CheckpointEvery" "--consumerName=$ConsumerName"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
