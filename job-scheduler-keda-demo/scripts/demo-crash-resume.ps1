. (Join-Path $PSScriptRoot 'common.ps1')
Use-DemoKubectlContext

$api = 'http://localhost:18080'
$jobKey = 'crash-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$body = @{
    jobKey = $jobKey
    totalUnits = 1200
    unitDelayMs = 25
    checkpointEvery = 100
    maxAttempts = 3
} | ConvertTo-Json
$created = Invoke-RestMethod "$api/api/jobs" -Method Post -ContentType 'application/json' -Body $body
Write-Host "Created crash/resume jobId=$($created.jobId)"

$pod = $null
for ($i = 0; $i -lt 90; $i++) {
    $job = Invoke-RestMethod "$api/api/jobs/$($created.jobId)"
    if ($job.state -eq 'RUNNING' -and $job.lastCompletedUnit -ge 100) {
        $pod = kubectl get pods -n job-demo -l app.kubernetes.io/component=worker -o jsonpath='{.items[0].metadata.name}'
        Write-Host "Before crash: pod=$pod checkpoint=$($job.lastCompletedUnit) token=$($job.fencingToken)"
        break
    }
    Start-Sleep -Seconds 2
}
if (-not $pod) { throw 'No running worker with a checkpoint was observed.' }

kubectl delete pod $pod -n job-demo --grace-period=0 --force
Write-Host 'Worker was force-deleted. Waiting for RabbitMQ redelivery, lease expiry, and resume.'

for ($i = 0; $i -lt 120; $i++) {
    $job = Invoke-RestMethod "$api/api/jobs/$($created.jobId)"
    Write-Host "state=$($job.state) attempt=$($job.attemptCount) checkpoint=$($job.lastCompletedUnit) token=$($job.fencingToken) worker=$($job.workerId)"
    if ($job.state -eq 'SUCCEEDED') {
        Write-Host 'Crash/resume demo passed.'
        exit 0
    }
    if ($job.state -eq 'FAILED') { throw 'Crash/resume job failed.' }
    Start-Sleep -Seconds 2
}
throw 'Timed out waiting for crash recovery.'
