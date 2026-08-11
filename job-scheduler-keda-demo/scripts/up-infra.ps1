. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Command docker
$demoRoot = Get-DemoRoot

docker compose --project-directory $demoRoot -f (Join-Path $demoRoot 'docker-compose.yml') up -d --wait
if ($LASTEXITCODE -ne 0) { throw 'Docker Compose infrastructure failed to become healthy.' }

Write-Host 'Postgres: localhost:15432 (database/user: jobdemo)'
Write-Host 'RabbitMQ AMQP: localhost:15673'
Write-Host 'RabbitMQ UI: http://localhost:15674 (jobdemo / jobdemo_password)'
