. (Join-Path $PSScriptRoot 'common.ps1')

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

$jobKey = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$body = @{ jobKey = $jobKey; durationMs = 500 } | ConvertTo-Json
$created = Invoke-RestMethod "$api/api/jobs" -Method Post -ContentType 'application/json' -Body $body
if (-not $created.eventId -or $created.jobKey -ne $jobKey) {
    throw 'API did not return a valid accepted event.'
}
Write-Host "Published eventId=$($created.eventId), jobKey=$jobKey"

for ($i = 0; $i -lt 120; $i++) {
    $line = docker exec job-demo-rabbitmq rabbitmqctl list_queues -q name messages_ready messages_unacknowledged |
        Where-Object { $_ -match '^demo\.jobs\.ready\s' }
    $parts = $line -split '\s+'
    $readyCount = if ($parts.Count -ge 2) { [int]$parts[1] } else { 0 }
    $unacked = if ($parts.Count -ge 3) { [int]$parts[2] } else { 0 }
    Write-Host "queueReady=$readyCount unacked=$unacked"
    if ($readyCount -eq 0 -and $unacked -eq 0) {
        Write-Host 'Smoke test passed.'
        exit 0
    }
    Start-Sleep -Seconds 2
}
throw 'Smoke event was not consumed.'
