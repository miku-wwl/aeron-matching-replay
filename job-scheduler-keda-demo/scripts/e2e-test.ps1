. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Command docker
Assert-Command kubectl
Use-DemoKubectlContext

$api = 'http://localhost:18080'
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod "$api/actuator/health/readiness" -TimeoutSec 3
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $ready) { throw 'API readiness did not become UP.' }

$scaledObjectReady = kubectl get scaledobject demo-worker-rabbitmq -n job-demo `
    -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}'
if ($scaledObjectReady -ne 'True') { throw 'KEDA ScaledObject is not Ready.' }

$existingReady = docker exec job-demo-rabbitmq rabbitmqctl list_queues `
    name messages_ready --formatter json | ConvertFrom-Json |
    Where-Object name -eq 'demo.jobs.ready' | Select-Object -ExpandProperty messages_ready
if ([int]$existingReady -ne 0) { throw 'Ready queue must be empty before the E2E test.' }

$runId = [Guid]::NewGuid().ToString('N').Substring(0, 10)
$scheduledKey = "e2e-scheduled-$runId"
$scheduledAt = (Get-Date).ToUniversalTime().AddSeconds(12).ToString('o')
$scheduledBody = @{
    jobKey = $scheduledKey
    totalUnits = 40
    unitDelayMs = 5
    checkpointEvery = 10
    scheduledAt = $scheduledAt
} | ConvertTo-Json
$scheduled = Invoke-RestMethod "$api/api/jobs" -Method Post `
    -ContentType 'application/json' -Body $scheduledBody
Start-Sleep -Seconds 3
$scheduledBeforeDue = Invoke-RestMethod "$api/api/jobs/$($scheduled.jobId)"
if ($scheduledBeforeDue.state -ne 'SCHEDULED') {
    throw "Future job dispatched too early: state=$($scheduledBeforeDue.state)"
}

for ($poll = 0; $poll -lt 60; $poll++) {
    $scheduledResult = Invoke-RestMethod "$api/api/jobs/$($scheduled.jobId)"
    if ($scheduledResult.state -eq 'SUCCEEDED') { break }
    Start-Sleep -Seconds 2
}
if ($scheduledResult.state -ne 'SUCCEEDED') { throw 'Scheduled job did not succeed.' }

# One simulated unit lasts longer than the 30-second lease. Passing this case
# proves that heartbeats continue during unit execution rather than only between units.
$longUnitBody = @{
    jobKey = "e2e-long-unit-$runId"
    totalUnits = 1
    unitDelayMs = 35000
    checkpointEvery = 1
    maxAttempts = 2
} | ConvertTo-Json
$longUnit = Invoke-RestMethod "$api/api/jobs" -Method Post `
    -ContentType 'application/json' -Body $longUnitBody

# Run this assertion before the burst. HPA scale-down may deliberately terminate
# any busy Pod; that graceful retry is valid behavior but would obscure this lease test.
for ($poll = 0; $poll -lt 60; $poll++) {
    $longUnitResult = Invoke-RestMethod "$api/api/jobs/$($longUnit.jobId)"
    if ($longUnitResult.state -eq 'SUCCEEDED') { break }
    Start-Sleep -Seconds 2
}
if ($longUnitResult.state -ne 'SUCCEEDED' -or $longUnitResult.attemptCount -ne 1 `
        -or $longUnitResult.lastCompletedUnit -ne 1) {
    throw "Long-unit heartbeat job failed: state=$($longUnitResult.state) " +
        "attempts=$($longUnitResult.attemptCount) checkpoint=$($longUnitResult.lastCompletedUnit)"
}

$count = 12
$prefix = "e2e-burst-$runId"
$jobs = @()
for ($i = 1; $i -le $count; $i++) {
    $body = @{
        jobKey = "$prefix-$i"
        totalUnits = 600
        unitDelayMs = 20
        checkpointEvery = 100
        maxAttempts = 3
    } | ConvertTo-Json
    $jobs += Invoke-RestMethod "$api/api/jobs" -Method Post `
        -ContentType 'application/json' -Body $body
}
Write-Host "Created scheduled job, long-unit heartbeat job, and $count burst jobs; runId=$runId"

$maxReplicas = 0
$succeeded = 0
for ($poll = 0; $poll -lt 120; $poll++) {
    $replicaText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.spec.replicas}'
    $replicas = if ($replicaText) { [int]$replicaText } else { 0 }
    $maxReplicas = [Math]::Max($maxReplicas, $replicas)
    $sql = "SELECT count(*) FROM demo_job WHERE job_key LIKE '$prefix-%' AND state='SUCCEEDED';"
    $succeeded = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $sql)
    if ($poll % 3 -eq 0) {
        Write-Host "replicas=$replicas maxReplicas=$maxReplicas burstSucceeded=$succeeded/$count"
    }
    if ($succeeded -eq $count) { break }
    Start-Sleep -Seconds 3
}
if ($succeeded -ne $count) { throw "Only $succeeded/$count burst jobs succeeded." }
if ($maxReplicas -lt 2) { throw "KEDA never scaled beyond one worker; max=$maxReplicas" }

$verifiedSql = @"
SELECT count(*)
FROM demo_job j
JOIN demo_checkpoint c ON c.job_key = j.job_key
WHERE j.job_key LIKE '$prefix-%'
  AND j.state = 'SUCCEEDED'
  AND c.last_completed_unit = 600;
"@
$verified = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $verifiedSql)
if ($verified -ne $count) {
    throw "Only $verified/$count jobs have the expected final checkpoint."
}

$unexpectedRetrySql = @"
SELECT count(*)
FROM demo_attempt a
JOIN demo_job j ON j.job_id = a.job_id
WHERE j.job_key LIKE '$prefix-%'
  AND a.status <> 'SUCCEEDED'
  AND coalesce(a.failure_code, '') <> 'GRACEFUL_SHUTDOWN';
"@
$unexpectedRetries = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $unexpectedRetrySql)
if ($unexpectedRetries -ne 0) {
    throw "Burst jobs had $unexpectedRetries non-graceful retry attempts."
}
$gracefulRetrySql = @"
SELECT count(*)
FROM demo_attempt a
JOIN demo_job j ON j.job_id = a.job_id
WHERE j.job_key LIKE '$prefix-%'
  AND a.failure_code = 'GRACEFUL_SHUTDOWN';
"@
$gracefulRetries = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $gracefulRetrySql)

$scaledToZero = $false
for ($poll = 0; $poll -lt 70; $poll++) {
    $replicaText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.spec.replicas}'
    $replicas = if ($replicaText) { [int]$replicaText } else { 0 }
    if ($replicas -eq 0) { $scaledToZero = $true; break }
    if ($poll % 5 -eq 0) { Write-Host "Waiting for cooldown: replicas=$replicas" }
    Start-Sleep -Seconds 3
}
if (-not $scaledToZero) { throw 'KEDA did not scale workers back to zero.' }

$queueRows = docker exec job-demo-rabbitmq rabbitmqctl list_queues `
    name messages_ready messages_unacknowledged --formatter json | ConvertFrom-Json
$readyQueue = $queueRows | Where-Object name -eq 'demo.jobs.ready'
if ([int]$readyQueue.messages_ready -ne 0 -or [int]$readyQueue.messages_unacknowledged -ne 0) {
    throw 'RabbitMQ ready queue was not drained.'
}

Write-Host "E2E PASSED: scheduled gating, long-unit heartbeats, outbox/MQ," `
    "maxWorkers=$maxReplicas, $count atomic checkpoints," `
    "gracefulScaleDownRetries=$gracefulRetries, queue drained, scale-to-zero."
