[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "runtime"))
$jvmArgs = @(
    "--add-opens=java.base/jdk.internal.misc=ALL-UNNAMED",
    "--add-opens=java.base/java.util.zip=ALL-UNNAMED"
)
$jar = Join-Path $repoRoot "aeron-infrastructure\target\aeron-infrastructure-1.0.0-SNAPSHOT-all.jar"

Push-Location $repoRoot
try {
    & java @jvmArgs "-Dreplay.runtime.dir=$runtimeRoot" -jar $jar
    exit $LASTEXITCODE
}
finally {
    Pop-Location
}
