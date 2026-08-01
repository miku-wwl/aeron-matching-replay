[CmdletBinding()]
param(
    [int]$OrderCount = 5000,
    [long]$Seed = 20210801,
    [int]$SymbolId = 1,
    [long]$PublishDelayMicros = 0
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "runtime"))
$jvmArgs = @(
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED"
)
$jar = Join-Path $repoRoot "matching-engine-app\target\matching-engine-app-1.0.0-SNAPSHOT-all.jar"

Push-Location $repoRoot
try {
    & java @jvmArgs "-Dreplay.runtime.dir=$runtimeRoot" -jar $jar `
        "--orderCount=$OrderCount" "--seed=$Seed" "--symbolId=$SymbolId" `
        "--publishDelayMicros=$PublishDelayMicros"
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
