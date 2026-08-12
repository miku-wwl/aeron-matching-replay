. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Command docker
Assert-Command kubectl
Use-DemoKubectlContext

& (Join-Path $PSScriptRoot 'smoke-test.ps1')
if ($LASTEXITCODE -ne 0) { throw 'Smoke test failed.' }

& (Join-Path $PSScriptRoot 'demo-burst.ps1') -Count 500 -DurationMs 1000 -TargetPods 20
if ($LASTEXITCODE -ne 0) { throw 'KEDA burst test failed.' }

Write-Host 'E2E passed: API -> RabbitMQ -> KEDA -> Worker.'
