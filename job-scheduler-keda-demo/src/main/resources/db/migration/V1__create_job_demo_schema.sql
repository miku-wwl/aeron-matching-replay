CREATE TABLE demo_job (
    job_id UUID PRIMARY KEY,
    job_key VARCHAR(160) NOT NULL UNIQUE,
    duration_ms INTEGER NOT NULL CHECK (duration_ms > 0),
    state VARCHAR(16) NOT NULL CHECK (state IN (
        'SCHEDULED', 'QUEUED', 'RUNNING', 'SUCCEEDED', 'FAILED'
    )),
    scheduled_at TIMESTAMPTZ NOT NULL,
    worker_id VARCHAR(200),
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ
);

CREATE INDEX demo_job_due_idx ON demo_job (scheduled_at, job_id)
    WHERE state = 'SCHEDULED';
CREATE INDEX demo_job_state_idx ON demo_job (state, created_at);
