[CmdletBinding(SupportsShouldProcess)]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$checkpointRoot = [System.IO.Path]::GetFullPath(
    (Join-Path $repoRoot "runtime\checkpoints"))
$expectedPrefix = $repoRoot.TrimEnd(
    [System.IO.Path]::DirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar

if (-not $checkpointRoot.StartsWith(
    $expectedPrefix,
    [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean outside repository: $checkpointRoot"
}

if ($PSCmdlet.ShouldProcess(
    $checkpointRoot,
    "Remove replay progress checkpoints and completion proofs")) {
    Get-ChildItem -LiteralPath $checkpointRoot -Force -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -ne ".gitkeep" } |
        Remove-Item -Recurse -Force
}

Write-Host "REPLAY_STATE_CLEAN"
Write-Host "checkpointDirectory=$checkpointRoot"
