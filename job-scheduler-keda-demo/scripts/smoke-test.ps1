. (Join-Path $PSScriptRoot 'common.ps1')

$api = 'http://localhost:18080'
$ready = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod "$api/actuator/health" -TimeoutSec 3
        if ($health.status -eq 'UP') { $ready = $true; break }
    } catch {
        Start-Sleep -Seconds 2
    }
}
if (-not $ready) { throw 'API did not become healthy.' }

$jobKey = 'smoke-' + [Guid]::NewGuid().ToString('N').Substring(0, 10)
$body = @{
    jobKey = $jobKey
    durationMs = 500
} | ConvertTo-Json
$created = Invoke-RestMethod "$api/api/jobs" -Method Post -ContentType 'application/json' -Body $body
Write-Host "Created jobId=$($created.jobId), jobKey=$jobKey"

for ($i = 0; $i -lt 120; $i++) {
    $job = Invoke-RestMethod "$api/api/jobs/$($created.jobId)"
    Write-Host "state=$($job.state), worker=$($job.workerId)"
    if ($job.state -eq 'SUCCEEDED') {
        Write-Host 'Smoke test passed.'
        exit 0
    }
    if ($job.state -eq 'FAILED') { throw 'Smoke job failed.' }
    Start-Sleep -Seconds 2
}
throw 'Timed out waiting for the smoke job.'
