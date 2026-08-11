CREATE TABLE demo_job (
    job_id UUID PRIMARY KEY,
    job_key VARCHAR(160) NOT NULL UNIQUE,
    total_units INTEGER NOT NULL CHECK (total_units > 0),
    unit_delay_ms INTEGER NOT NULL CHECK (unit_delay_ms >= 0),
    checkpoint_every INTEGER NOT NULL CHECK (checkpoint_every > 0),
    state VARCHAR(24) NOT NULL CHECK (state IN (
        'SCHEDULED', 'DISPATCHING', 'QUEUED', 'RUNNING',
        'RETRY_WAIT', 'SUCCEEDED', 'FAILED', 'CANCELLED'
    )),
    scheduled_at TIMESTAMPTZ NOT NULL,
    next_run_at TIMESTAMPTZ NOT NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    max_attempts INTEGER NOT NULL CHECK (max_attempts > 0),
    fail_until_attempt INTEGER NOT NULL DEFAULT 0 CHECK (fail_until_attempt >= 0),
    worker_id VARCHAR(200),
    current_fencing_token BIGINT,
    failure_code VARCHAR(100),
    version BIGINT NOT NULL DEFAULT 0,
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX demo_job_due_idx ON demo_job (next_run_at, job_id)
    WHERE state IN ('SCHEDULED', 'RETRY_WAIT');
CREATE INDEX demo_job_state_idx ON demo_job (state, updated_at);

CREATE TABLE demo_outbox (
    outbox_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES demo_job(job_id),
    event_type VARCHAR(80) NOT NULL,
    payload JSONB NOT NULL,
    published_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE INDEX demo_outbox_pending_idx ON demo_outbox (created_at)
    WHERE published_at IS NULL;

CREATE TABLE demo_lease (
    resource_key VARCHAR(200) PRIMARY KEY,
    owner_id VARCHAR(200) NOT NULL,
    lease_until TIMESTAMPTZ NOT NULL,
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE demo_checkpoint (
    job_key VARCHAR(160) PRIMARY KEY,
    last_completed_unit INTEGER NOT NULL CHECK (last_completed_unit >= 0),
    checksum BIGINT NOT NULL,
    fencing_token BIGINT NOT NULL CHECK (fencing_token > 0),
    version BIGINT NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);

CREATE TABLE demo_attempt (
    attempt_id UUID PRIMARY KEY,
    job_id UUID NOT NULL REFERENCES demo_job(job_id),
    attempt_number INTEGER NOT NULL,
    worker_id VARCHAR(200) NOT NULL,
    fencing_token BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    completed_at TIMESTAMPTZ,
    status VARCHAR(32) NOT NULL,
    failure_code VARCHAR(100)
);

CREATE INDEX demo_attempt_job_idx ON demo_attempt (job_id, started_at);
