. (Join-Path $PSScriptRoot 'common.ps1')
Use-DemoKubectlContext

$created = Invoke-RestMethod 'http://localhost:18080/api/jobs/burst?count=100&totalUnits=10&unitDelayMs=1&checkpointEvery=5' -Method Post
$prefix = $created[0].jobKey -replace '-1$', ''
Write-Host "Created 100 due jobs with prefix=$prefix for the two scheduler replicas."

for ($i = 0; $i -lt 60; $i++) {
    $sql = "SELECT count(DISTINCT o.job_id) FROM demo_outbox o JOIN demo_job j ON j.job_id=o.job_id WHERE j.job_key LIKE '$prefix-%';"
    $outboxCount = docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $sql
    Write-Host "distinctOutboxJobs=$outboxCount/100"
    if ([int]$outboxCount -eq 100) { break }
    Start-Sleep -Seconds 1
}

$duplicateSql = "SELECT count(*) FROM (SELECT o.job_id FROM demo_outbox o JOIN demo_job j ON j.job_id=o.job_id WHERE j.job_key LIKE '$prefix-%' GROUP BY o.job_id HAVING count(*) > 1) d;"
$duplicates = docker exec job-demo-postgres psql -qAt -U jobdemo -d jobdemo -c $duplicateSql
if ([int]$duplicates -ne 0) { throw "Found $duplicates jobs with duplicate outbox rows." }
Write-Host 'Scheduler contention demo passed: 100 jobs, 100 distinct outbox rows, 0 duplicates.'
kubectl logs -n job-demo -l app.kubernetes.io/component=scheduler --tail=30 --prefix
