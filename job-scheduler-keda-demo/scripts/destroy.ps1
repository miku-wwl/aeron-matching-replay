param(
    [switch] $RemoveData
)

. (Join-Path $PSScriptRoot 'common.ps1')
Update-ProcessPath

if (Get-Command k3d -ErrorAction SilentlyContinue) {
    k3d cluster delete job-demo
}

$demoRoot = Get-DemoRoot
$demoEnvironment = Get-DemoEnvironment -CreateIfMissing
if ($RemoveData) {
    docker compose --env-file $demoEnvironment.Path --project-directory $demoRoot `
        -f (Join-Path $demoRoot 'docker-compose.yml') down --volumes
    Write-Host 'Removed the demo container and the RabbitMQ data volume.'
} else {
    docker compose --env-file $demoEnvironment.Path --project-directory $demoRoot `
        -f (Join-Path $demoRoot 'docker-compose.yml') down
    Write-Host 'Removed the demo container; the RabbitMQ data volume was preserved.'
}
