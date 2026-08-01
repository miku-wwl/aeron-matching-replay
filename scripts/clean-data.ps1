[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$runtimeRoot = [System.IO.Path]::GetFullPath((Join-Path $repoRoot "runtime"))
$repoPrefix = $repoRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
    [System.IO.Path]::DirectorySeparatorChar

if (-not $runtimeRoot.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to clean runtime outside repository: $runtimeRoot"
}

$directories = @("aeron", "archive", "checkpoints", "manifests", "logs", "pids")
foreach ($name in $directories) {
    $target = [System.IO.Path]::GetFullPath((Join-Path $runtimeRoot $name))
    if (-not $target.StartsWith(
        $runtimeRoot.TrimEnd([System.IO.Path]::DirectorySeparatorChar) +
            [System.IO.Path]::DirectorySeparatorChar,
        [System.StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to clean unexpected path: $target"
    }
    if (Test-Path -LiteralPath $target) {
        Remove-Item -LiteralPath $target -Recurse -Force
    }
    New-Item -ItemType Directory -Path $target -Force | Out-Null
    New-Item -ItemType File -Path (Join-Path $target ".gitkeep") -Force | Out-Null
}

Write-Host "RUNTIME_CLEAN"
Write-Host "runtimeDirectory=$runtimeRoot"
