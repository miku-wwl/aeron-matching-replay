[CmdletBinding()]
param(
    [string]$AeronDirectory,
    [string]$CheckpointDirectory = ".\runtime\checkpoints",
    [int]$Port = 8080
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$env:MAVEN_OPTS = "--add-opens java.base/jdk.internal.misc=ALL-UNNAMED --add-opens java.base/java.util.zip=ALL-UNNAMED"
$env:SERVER_PORT = $Port.ToString()
$env:REPLAY_CHECKPOINT_DIR = [System.IO.Path]::GetFullPath(
    (Join-Path $repoRoot $CheckpointDirectory))

if ($AeronDirectory) {
    $env:AERON_DIR = [System.IO.Path]::GetFullPath($AeronDirectory)
}

Push-Location $repoRoot
try {
    & ".\mvnw.cmd" -ntp spring-boot:run
    if ($LASTEXITCODE -ne 0) {
        throw "Replay service exited with code $LASTEXITCODE"
    }
}
finally {
    Pop-Location
}
