. (Join-Path $PSScriptRoot 'common.ps1')
Use-DemoKubectlContext

$api = 'http://localhost:18080'
$jobKey = 'outbox-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
Write-Host 'Stopping RabbitMQ to create the database-commit / MQ-publish failure window.'
docker stop job-demo-rabbitmq | Out-Null

try {
    $body = @{
        jobKey = $jobKey
        totalUnits = 100
        unitDelayMs = 5
        checkpointEvery = 20
    } | ConvertTo-Json
    $created = Invoke-RestMethod "$api/api/jobs" -Method Post -ContentType 'application/json' -Body $body

    $pending = $false
    for ($i = 0; $i -lt 30; $i++) {
        $sql = "SELECT count(*) FROM demo_outbox o JOIN demo_job j ON j.job_id=o.job_id WHERE j.job_key='$jobKey' AND o.published_at IS NULL;"
        $count = docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $sql
        if ([int]$count -eq 1) { $pending = $true; break }
        Start-Sleep -Seconds 1
    }
    if (-not $pending) { throw 'The committed pending outbox row was not observed.' }
    Write-Host 'Observed one committed, unpublished outbox row while RabbitMQ was unavailable.'
} finally {
    docker start job-demo-rabbitmq | Out-Null
}

for ($i = 0; $i -lt 120; $i++) {
    try {
        $job = Invoke-RestMethod "$api/api/jobs/$($created.jobId)" -TimeoutSec 3
        Write-Host "state=$($job.state) checkpoint=$($job.lastCompletedUnit)"
        if ($job.state -eq 'SUCCEEDED') {
            Write-Host 'Outbox recovery demo passed: publishing resumed and the job completed.'
            exit 0
        }
    } catch {
        # API readiness can briefly be DOWN while the shared RabbitMQ container restarts.
    }
    Start-Sleep -Seconds 2
}
throw 'Timed out waiting for outbox recovery.'
