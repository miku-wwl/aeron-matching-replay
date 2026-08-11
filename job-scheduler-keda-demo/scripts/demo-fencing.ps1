. (Join-Path $PSScriptRoot 'common.ps1')

Assert-Command docker
$resource = 'fencing-demo-' + [Guid]::NewGuid().ToString('N').Substring(0, 8)
$sql = @"
\set resource '$resource'
DELETE FROM demo_checkpoint WHERE job_key = :'resource';
DELETE FROM demo_lease WHERE resource_key = :'resource';

INSERT INTO demo_lease(resource_key, owner_id, lease_until, fencing_token)
VALUES (:'resource', 'worker-a', clock_timestamp() + interval '30 seconds', 1);
INSERT INTO demo_checkpoint(job_key, last_completed_unit, checksum, fencing_token)
VALUES (:'resource', 100, 100100, 1);

UPDATE demo_lease SET lease_until = clock_timestamp() - interval '1 second'
WHERE resource_key = :'resource';
INSERT INTO demo_lease(resource_key, owner_id, lease_until, fencing_token)
VALUES (:'resource', 'worker-b', clock_timestamp() + interval '30 seconds', 1)
ON CONFLICT (resource_key) DO UPDATE
SET owner_id = EXCLUDED.owner_id,
    lease_until = EXCLUDED.lease_until,
    fencing_token = demo_lease.fencing_token + 1
WHERE demo_lease.lease_until < clock_timestamp();

UPDATE demo_checkpoint
SET last_completed_unit = 200, checksum = 200200, fencing_token = 2, version = version + 1
WHERE job_key = :'resource' AND fencing_token <= 2 AND last_completed_unit <= 200;

\echo 'The next stale worker update must report UPDATE 0:'
UPDATE demo_checkpoint
SET last_completed_unit = 150, checksum = 150150, fencing_token = 1, version = version + 1
WHERE job_key = :'resource' AND fencing_token <= 1 AND last_completed_unit <= 150;

SELECT l.resource_key, l.owner_id, l.fencing_token AS active_token,
       c.last_completed_unit, c.fencing_token AS checkpoint_token
FROM demo_lease l JOIN demo_checkpoint c ON c.job_key = l.resource_key
WHERE l.resource_key = :'resource';
"@

$sql | docker exec -i job-demo-postgres psql -v ON_ERROR_STOP=1 -U jobdemo -d jobdemo
if ($LASTEXITCODE -ne 0) { throw 'Fencing demonstration SQL failed.' }
