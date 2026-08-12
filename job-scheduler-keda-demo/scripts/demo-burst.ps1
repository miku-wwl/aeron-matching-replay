param(
    [int] $Count = 500,
    [int] $DurationMs = 1000,
    [int] $TargetPods = 20
)

. (Join-Path $PSScriptRoot 'common.ps1')
Use-DemoKubectlContext

$api = 'http://localhost:18080'
$burst = Invoke-RestMethod `
    "$api/api/jobs/burst?count=$Count&durationMs=$DurationMs" -Method Post
Write-Host "Published $($burst.count) events with prefix=$($burst.prefix)."

$peak = 0
$queueDrained = $false
for ($i = 0; $i -lt 180; $i++) {
    $desiredText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.spec.replicas}'
    $readyText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.status.readyReplicas}'
    $desired = if ($desiredText) { [int]$desiredText } else { 0 }
    $ready = if ($readyText) { [int]$readyText } else { 0 }
    $peak = [Math]::Max($peak, $ready)
    $line = docker exec job-demo-rabbitmq rabbitmqctl list_queues -q name messages_ready messages_unacknowledged |
        Where-Object { $_ -match '^demo\.jobs\.ready\s' }
    $parts = $line -split '\s+'
    $queueReady = if ($parts.Count -ge 2) { [int]$parts[1] } else { 0 }
    $unacked = if ($parts.Count -ge 3) { [int]$parts[2] } else { 0 }
    Write-Host "desired=$desired ready=$ready queueReady=$queueReady unacked=$unacked"
    if ($peak -ge $TargetPods -and $queueReady -eq 0 -and $unacked -eq 0) {
        $queueDrained = $true
        break
    }
    Start-Sleep -Seconds 2
}

if ($peak -lt $TargetPods) { throw "Expected $TargetPods ready workers, peak was $peak." }
if (-not $queueDrained) { throw 'RabbitMQ ready queue did not drain.' }

for ($i = 0; $i -lt 90; $i++) {
    $desiredText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.spec.replicas}'
    $desired = if ($desiredText) { [int]$desiredText } else { 0 }
    if ($desired -eq 0) { break }
    Start-Sleep -Seconds 2
}
if ($desired -ne 0) { throw 'KEDA did not scale workers back to zero.' }

Write-Host "KEDA demo passed: 0 -> $peak -> 0; events=$Count."
