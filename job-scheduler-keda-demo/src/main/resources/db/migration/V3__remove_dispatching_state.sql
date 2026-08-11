UPDATE demo_job
SET state = 'QUEUED', updated_at = clock_timestamp()
WHERE state = 'DISPATCHING';

ALTER TABLE demo_job DROP CONSTRAINT demo_job_state_check;
ALTER TABLE demo_job ADD CONSTRAINT demo_job_state_check CHECK (state IN (
    'SCHEDULED', 'QUEUED', 'RUNNING', 'RETRY_WAIT',
    'SUCCEEDED', 'FAILED', 'CANCELLED'
));
