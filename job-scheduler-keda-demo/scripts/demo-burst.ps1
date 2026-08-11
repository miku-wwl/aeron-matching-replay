param(
    [int] $Count = 50,
    [int] $TotalUnits = 200,
    [int] $UnitDelayMs = 20
)

. (Join-Path $PSScriptRoot 'common.ps1')
Use-DemoKubectlContext

$uri = "http://localhost:18080/api/jobs/burst?count=$Count&totalUnits=$TotalUnits&unitDelayMs=$UnitDelayMs&checkpointEvery=25"
$created = Invoke-RestMethod $uri -Method Post
$prefix = $created[0].jobKey -replace '-1$', ''
Write-Host "Created $($created.Count) jobs with prefix=$prefix. Watching queue-driven scaling for up to 3 minutes."

for ($i = 0; $i -lt 90; $i++) {
    $succeededSql = "SELECT count(*) FROM demo_job WHERE job_key LIKE '$prefix-%' AND state='SUCCEEDED';"
    $failedSql = "SELECT count(*) FROM demo_job WHERE job_key LIKE '$prefix-%' AND state='FAILED';"
    $succeeded = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $succeededSql)
    $failed = [int](docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $failedSql)
    $replicas = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.status.replicas}'
    $ready = kubectl get deployment demo-worker -n job-demo -o jsonpath='{.status.readyReplicas}'
    Write-Host "workerReplicas=$replicas ready=$ready burstSucceeded=$succeeded/$Count burstFailed=$failed"
    if ($failed -gt 0) { throw "$failed burst jobs failed." }
    if ($succeeded -eq $Count) { break }
    Start-Sleep -Seconds 2
}

if ($succeeded -ne $Count) { throw "Timed out with only $succeeded/$Count burst jobs succeeded." }

kubectl get scaledobject,hpa,deploy,pods -n job-demo
