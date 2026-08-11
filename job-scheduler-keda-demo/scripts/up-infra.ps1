. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Command docker
$demoRoot = Get-DemoRoot
$demoEnvironment = Get-DemoEnvironment -CreateIfMissing

docker compose --env-file $demoEnvironment.Path --project-directory $demoRoot `
    -f (Join-Path $demoRoot 'docker-compose.yml') up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose infrastructure failed to become healthy.' }
Sync-DemoInfrastructureCredentials -DemoEnvironment $demoEnvironment

Write-Host "Postgres: localhost:15432 (database/user: $($demoEnvironment.PostgresDatabase)/$($demoEnvironment.PostgresUser))"
Write-Host 'RabbitMQ AMQP: localhost:15673'
Write-Host "RabbitMQ UI: http://localhost:15674 (user: $($demoEnvironment.RabbitUsername); password: .env.local)"
