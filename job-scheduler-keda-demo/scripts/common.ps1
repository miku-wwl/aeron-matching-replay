$ErrorActionPreference = 'Stop'

function Get-DemoRoot {
    return (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
}

function Update-ProcessPath {
    $machinePath = [Environment]::GetEnvironmentVariable('Path', 'Machine')
    $userPath = [Environment]::GetEnvironmentVariable('Path', 'User')
    $env:Path = "$machinePath;$userPath"
}

function Assert-Command([string] $Name) {
    if (-not (Get-Command $Name -ErrorAction SilentlyContinue)) {
        throw "Required command '$Name' was not found. Run scripts/prerequisites.ps1 first."
    }
}

function Use-DemoKubectlContext {
    kubectl config use-context k3d-job-demo | Out-Null
}
