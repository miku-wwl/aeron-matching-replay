. (Join-Path $PSScriptRoot 'common.ps1')

Update-ProcessPath
Assert-Command docker
Assert-Command k3d
Assert-Command kubectl
Assert-Command helm

k3d cluster get job-demo *> $null
if ($LASTEXITCODE -ne 0) {
    k3d cluster create job-demo `
        --servers 1 `
        --agents 2 `
        --api-port 16550 `
        --port '18080:30080@agent:0' `
        --wait
    if ($LASTEXITCODE -ne 0) { throw 'k3d cluster creation failed.' }
} else {
    Write-Host 'k3d cluster job-demo already exists.'
}

Use-DemoKubectlContext
if ($IsWindows) {
    # Docker Desktop may make k3d write host.docker.internal here even though
    # the published API port is reachable from Windows on loopback.
    kubectl config set-cluster k3d-job-demo --server=https://127.0.0.1:16550 | Out-Null
}

$apiReady = $false
for ($i = 0; $i -lt 60; $i++) {
    kubectl get --raw='/readyz' *> $null
    if ($LASTEXITCODE -eq 0) { $apiReady = $true; break }
    Start-Sleep -Seconds 2
}
if (-not $apiReady) { throw 'k3d Kubernetes API did not become ready.' }

helm repo add kedacore https://kedacore.github.io/charts --force-update
helm repo update
helm upgrade --install keda kedacore/keda `
    --namespace keda `
    --create-namespace `
    --wait `
    --timeout 5m
if ($LASTEXITCODE -ne 0) { throw 'KEDA Helm installation failed.' }

kubectl wait --for=condition=Available deployment/keda-operator -n keda --timeout=180s
kubectl get nodes -o wide
helm list -n keda
