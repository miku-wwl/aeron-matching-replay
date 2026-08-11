. (Join-Path $PSScriptRoot 'common.ps1')

$api = 'http://localhost:18080'
$demoEnvironment = Get-DemoEnvironment
$credentials = [Convert]::ToBase64String(
    [Text.Encoding]::ASCII.GetBytes(
        "$($demoEnvironment.RabbitUsername):$($demoEnvironment.RabbitPassword)"))
$initialQueue = Invoke-RestMethod `
    'http://localhost:15674/api/queues/%2F/demo.jobs.dlq' `
    -Headers @{ Authorization = "Basic $credentials" }
$initialDlqCount = [int]$initialQueue.messages_ready
$jobKey = 'dlq-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$body = @{
    jobKey = $jobKey
    totalUnits = 500
    unitDelayMs = 2
    checkpointEvery = 20
    maxAttempts = 3
    failUntilAttempt = 999
} | ConvertTo-Json
$created = Invoke-RestMethod "$api/api/jobs" -Method Post -ContentType 'application/json' -Body $body
Write-Host "Created always-failing jobId=$($created.jobId)"

for ($i = 0; $i -lt 120; $i++) {
    $job = Invoke-RestMethod "$api/api/jobs/$($created.jobId)"
    Write-Host "state=$($job.state) attempt=$($job.attemptCount) checkpoint=$($job.lastCompletedUnit) failure=$($job.failureCode)"
    if ($job.state -eq 'FAILED') {
        $dlqCount = 0
        for ($poll = 0; $poll -lt 20; $poll++) {
            $queue = Invoke-RestMethod `
                'http://localhost:15674/api/queues/%2F/demo.jobs.dlq' `
                -Headers @{ Authorization = "Basic $credentials" }
            $dlqCount = [int]$queue.messages_ready
            if ($dlqCount -gt $initialDlqCount) { break }
            Start-Sleep -Milliseconds 500
        }
        if ($dlqCount -le $initialDlqCount) {
            throw 'The failed message was not present in demo.jobs.dlq.'
        }
        Write-Host "Retry/DLQ demo passed; DLQ ready messages=$dlqCount."
        exit 0
    }
    Start-Sleep -Seconds 2
}
throw 'Timed out waiting for retry exhaustion.'
