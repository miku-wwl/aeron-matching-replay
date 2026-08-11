param(
    [switch] $SkipBuild
)

. (Join-Path $PSScriptRoot 'common.ps1')

Update-ProcessPath
Assert-Command docker
Assert-Command k3d
Assert-Command kubectl
Assert-Command mvn
Use-DemoKubectlContext

$demoRoot = Get-DemoRoot
if (-not $SkipBuild) {
    Push-Location $demoRoot
    try {
        mvn -B -ntp package
        if ($LASTEXITCODE -ne 0) { throw 'Maven build/tests failed.' }
        docker build -t job-scheduler-keda-demo:local .
        if ($LASTEXITCODE -ne 0) { throw 'Docker image build failed.' }
    } finally {
        Pop-Location
    }
}

k3d image import job-scheduler-keda-demo:local --cluster job-demo
if ($LASTEXITCODE -ne 0) { throw 'Could not import the image into k3d.' }

kubectl apply -k (Join-Path $demoRoot 'kubernetes')
if ($LASTEXITCODE -ne 0) { throw 'Kubernetes apply failed.' }
kubectl rollout restart deployment/demo-api deployment/demo-scheduler deployment/demo-worker -n job-demo
kubectl rollout status deployment/demo-api -n job-demo --timeout=180s
kubectl rollout status deployment/demo-scheduler -n job-demo --timeout=180s
kubectl wait --for=condition=Ready scaledobject/demo-worker-rabbitmq -n job-demo --timeout=120s
kubectl get deployments,pods,scaledobject,hpa -n job-demo

Write-Host 'API: http://localhost:18080'
