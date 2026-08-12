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
Write-Host "Created $($burst.count) jobs with prefix=$($burst.prefix)."

$peak = 0
for ($i = 0; $i -lt 120; $i++) {
    $desiredText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.spec.replicas}'
    $readyText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.status.readyReplicas}'
    $desired = if ($desiredText) { [int]$desiredText } else { 0 }
    $ready = if ($readyText) { [int]$readyText } else { 0 }
    $peak = [Math]::Max($peak, $ready)
    $sql = "SELECT count(*) FROM demo_job WHERE job_key LIKE '$($burst.prefix)-%' AND state='SUCCEEDED';"
    $succeeded = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $sql)
    Write-Host "desired=$desired ready=$ready succeeded=$succeeded/$Count"
    if ($succeeded -eq $Count) { break }
    Start-Sleep -Seconds 2
}

if ($succeeded -ne $Count) { throw "Only $succeeded/$Count jobs succeeded." }
if ($peak -lt $TargetPods) { throw "Expected $TargetPods ready workers, peak was $peak." }

for ($i = 0; $i -lt 90; $i++) {
    $desiredText = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.spec.replicas}'
    $desired = if ($desiredText) { [int]$desiredText } else { 0 }
    if ($desired -eq 0) { break }
    Start-Sleep -Seconds 2
}
if ($desired -ne 0) { throw 'KEDA did not scale workers back to zero.' }

Write-Host "KEDA demo passed: 0 -> $peak -> 0; jobs=$Count."
