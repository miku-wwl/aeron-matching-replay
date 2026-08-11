. (Join-Path $PSScriptRoot 'common.ps1')

Update-ProcessPath
Assert-Command docker
Assert-Command kubectl
Assert-Command java
Assert-Command mvn

if (-not (Get-Command winget -ErrorAction SilentlyContinue)) {
    throw 'WinGet is required to install the missing k3d/Helm CLI tools on Windows.'
}

if (-not (Get-Command k3d -ErrorAction SilentlyContinue)) {
    winget install --id k3d.k3d --exact --accept-package-agreements --accept-source-agreements --silent
    Update-ProcessPath
    Assert-Command k3d
}

if (-not (Get-Command helm -ErrorAction SilentlyContinue)) {
    winget install --id Helm.Helm --exact --accept-package-agreements --accept-source-agreements --silent
    Update-ProcessPath
    Assert-Command helm
}

Update-ProcessPath
Assert-Command k3d
Assert-Command helm

Write-Host 'Prerequisites are ready:'
java -version
mvn -version
docker version --format 'Docker {{.Client.Version}} / server {{.Server.Version}}'
kubectl version --client
k3d version
helm version --short
