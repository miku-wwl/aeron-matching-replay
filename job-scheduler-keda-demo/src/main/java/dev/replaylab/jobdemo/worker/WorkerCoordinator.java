package dev.replaylab.jobdemo.worker;

import dev.replaylab.jobdemo.domain.JobClaim;
import dev.replaylab.jobdemo.domain.JobState;
import dev.replaylab.jobdemo.metrics.DemoMetrics;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

@Service
public class WorkerCoordinator {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final DemoMetrics metrics;
    private final long leaseMillis;

    public WorkerCoordinator(JdbcTemplate jdbc,
                             TransactionTemplate transactions,
                             DemoMetrics metrics,
                             @Value("${app.worker.lease-duration:30s}") Duration leaseDuration) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.metrics = metrics;
        this.leaseMillis = leaseDuration.toMillis();
        if (leaseMillis < 3_000) {
            throw new IllegalArgumentException("Lease duration must be at least 3 seconds");
        }
    }

    public JobClaim claim(UUID jobId, String workerId) {
        JobClaim result = transactions.execute(status -> claimInTransaction(jobId, workerId));
        if (result == null) {
            throw new IllegalStateException("Claim transaction returned no result");
        }
        if (result.outcome() == JobClaim.Outcome.ACQUIRED) {
            metrics.leaseAcquired().increment();
        } else if (result.outcome() == JobClaim.Outcome.BUSY) {
            metrics.leaseConflicts().increment();
        }
        return result;
    }

    private JobClaim claimInTransaction(UUID jobId, String workerId) {
        List<JobRow> jobs = jdbc.query("""
                SELECT job_id, job_key, total_units, unit_delay_ms, checkpoint_every, state,
                       attempt_count, max_attempts, fail_until_attempt
                FROM demo_job
                WHERE job_id = ?
                FOR UPDATE
                """, this::mapJob, jobId);
        if (jobs.isEmpty()) {
            return JobClaim.outcome(JobClaim.Outcome.NOT_FOUND, jobId);
        }
        JobRow job = jobs.getFirst();
        if (job.state() != JobState.QUEUED && job.state() != JobState.RUNNING) {
            return JobClaim.outcome(JobClaim.Outcome.DUPLICATE_OR_TERMINAL, jobId);
        }

        List<Long> tokens = jdbc.query("""
                INSERT INTO demo_lease(resource_key, owner_id, lease_until, fencing_token)
                VALUES (?, ?, clock_timestamp() + (? * interval '1 millisecond'), 1)
                ON CONFLICT (resource_key) DO UPDATE
                SET owner_id = EXCLUDED.owner_id,
                    lease_until = EXCLUDED.lease_until,
                    fencing_token = demo_lease.fencing_token + 1,
                    updated_at = clock_timestamp()
                WHERE demo_lease.lease_until < clock_timestamp()
                RETURNING fencing_token
                """, (rs, rowNum) -> rs.getLong(1), job.jobKey(), workerId, leaseMillis);
        if (tokens.isEmpty()) {
            return JobClaim.outcome(JobClaim.Outcome.BUSY, jobId);
        }

        long fencingToken = tokens.getFirst();
        int attemptNumber = job.attemptCount() + 1;
        UUID attemptId = UUID.randomUUID();
        int changed = jdbc.update("""
                UPDATE demo_job
                SET state = 'RUNNING', worker_id = ?, current_fencing_token = ?,
                    attempt_count = ?, failure_code = NULL,
                    started_at = clock_timestamp(), completed_at = NULL,
                    version = version + 1, updated_at = clock_timestamp()
                WHERE job_id = ? AND state IN ('QUEUED', 'RUNNING')
                """, workerId, fencingToken, attemptNumber, jobId);
        if (changed != 1) {
            throw new IllegalStateException("Claimed lease but could not transition job " + jobId);
        }
        jdbc.update("""
                INSERT INTO demo_attempt(
                    attempt_id, job_id, attempt_number, worker_id, fencing_token, status
                ) VALUES (?, ?, ?, ?, ?, 'RUNNING')
                """, attemptId, jobId, attemptNumber, workerId, fencingToken);

        Checkpoint checkpoint = loadCheckpoint(job.jobKey());
        return new JobClaim(JobClaim.Outcome.ACQUIRED, job.jobId(), job.jobKey(),
                job.totalUnits(), job.unitDelayMs(), job.checkpointEvery(), attemptNumber,
                job.maxAttempts(), job.failUntilAttempt(), attemptId, workerId, fencingToken,
                checkpoint.unit(), checkpoint.checksum());
    }

    public void heartbeat(JobClaim claim) {
        int renewed = jdbc.update("""
                UPDATE demo_lease
                SET lease_until = clock_timestamp() + (? * interval '1 millisecond'),
                    updated_at = clock_timestamp()
                WHERE resource_key = ? AND owner_id = ? AND fencing_token = ?
                  AND lease_until >= clock_timestamp()
                """, leaseMillis, claim.jobKey(), claim.workerId(), claim.fencingToken());
        if (renewed != 1) {
            metrics.leaseLost().increment();
            throw new LeaseLostException("Lease heartbeat rejected for jobKey=" + claim.jobKey()
                    + " token=" + claim.fencingToken());
        }
    }

    public void saveCheckpoint(JobClaim claim, int unit, long checksum) {
        transactions.executeWithoutResult(status -> {
            assertValidLease(claim);
            upsertCheckpoint(claim, unit, checksum);
        });
        metrics.checkpointUpdates().increment();
    }

    public void succeed(JobClaim claim, int unit, long checksum) {
        transactions.executeWithoutResult(status -> {
            assertValidLease(claim);
            upsertCheckpoint(claim, unit, checksum);
            int changed = jdbc.update("""
                    UPDATE demo_job
                    SET state = 'SUCCEEDED', completed_at = clock_timestamp(), failure_code = NULL,
                        version = version + 1, updated_at = clock_timestamp()
                    WHERE job_id = ? AND state = 'RUNNING' AND worker_id = ?
                      AND current_fencing_token = ?
                    """, claim.jobId(), claim.workerId(), claim.fencingToken());
            if (changed != 1) {
                throw new LeaseLostException("Stale worker cannot complete jobId=" + claim.jobId());
            }
            finishAttempt(claim, "SUCCEEDED", null);
            expireLease(claim);
        });
        metrics.checkpointUpdates().increment();
    }

    public FailureResult fail(JobClaim claim, int unit, long checksum, String failureCode) {
        Duration backoff = RetryBackoff.forAttempt(claim.attemptCount());
        boolean terminal = claim.attemptCount() >= claim.maxAttempts();
        transactions.executeWithoutResult(status -> {
            assertValidLease(claim);
            upsertCheckpoint(claim, unit, checksum);
            String nextState = terminal ? "FAILED" : "RETRY_WAIT";
            int changed = jdbc.update("""
                    UPDATE demo_job
                    SET state = ?, failure_code = ?,
                        next_run_at = clock_timestamp() + (? * interval '1 millisecond'),
                        completed_at = CASE WHEN ? THEN clock_timestamp() ELSE NULL END,
                        version = version + 1, updated_at = clock_timestamp()
                    WHERE job_id = ? AND state = 'RUNNING' AND worker_id = ?
                      AND current_fencing_token = ?
                    """, nextState, failureCode, backoff.toMillis(), terminal,
                    claim.jobId(), claim.workerId(), claim.fencingToken());
            if (changed != 1) {
                throw new LeaseLostException("Stale worker cannot fail/retry jobId=" + claim.jobId());
            }
            finishAttempt(claim, terminal ? "FAILED" : "RETRY_WAIT", failureCode);
            expireLease(claim);
        });
        metrics.checkpointUpdates().increment();
        return new FailureResult(terminal, backoff.toSeconds());
    }

    private void assertValidLease(JobClaim claim) {
        List<Boolean> valid = jdbc.query("""
                SELECT owner_id = ? AND fencing_token = ?
                       AND lease_until >= clock_timestamp() AS valid
                FROM demo_lease
                WHERE resource_key = ?
                FOR UPDATE
                """, (rs, rowNum) -> rs.getBoolean("valid"),
                claim.workerId(), claim.fencingToken(), claim.jobKey());
        if (valid.isEmpty() || !valid.getFirst()) {
            metrics.leaseLost().increment();
            throw new LeaseLostException("Lease/fencing validation rejected jobKey=" + claim.jobKey()
                    + " owner=" + claim.workerId() + " token=" + claim.fencingToken());
        }
    }

    private void upsertCheckpoint(JobClaim claim, int unit, long checksum) {
        int changed = jdbc.update("""
                INSERT INTO demo_checkpoint(
                    job_key, last_completed_unit, checksum, fencing_token, version
                ) VALUES (?, ?, ?, ?, 1)
                ON CONFLICT (job_key) DO UPDATE
                SET last_completed_unit = EXCLUDED.last_completed_unit,
                    checksum = EXCLUDED.checksum,
                    fencing_token = EXCLUDED.fencing_token,
                    version = demo_checkpoint.version + 1,
                    updated_at = clock_timestamp()
                WHERE EXCLUDED.last_completed_unit >= demo_checkpoint.last_completed_unit
                  AND EXCLUDED.fencing_token >= demo_checkpoint.fencing_token
                """, claim.jobKey(), unit, checksum, claim.fencingToken());
        if (changed != 1) {
            metrics.leaseLost().increment();
            throw new LeaseLostException("Checkpoint regression or stale token rejected for jobKey="
                    + claim.jobKey() + " incomingUnit=" + unit + " token=" + claim.fencingToken());
        }
    }

    private void finishAttempt(JobClaim claim, String status, String failureCode) {
        int changed = jdbc.update("""
                UPDATE demo_attempt
                SET status = ?, failure_code = ?, completed_at = clock_timestamp()
                WHERE attempt_id = ? AND status = 'RUNNING'
                """, status, failureCode, claim.attemptId());
        if (changed != 1) {
            throw new IllegalStateException("Could not complete attemptId=" + claim.attemptId());
        }
    }

    private void expireLease(JobClaim claim) {
        int changed = jdbc.update("""
                UPDATE demo_lease
                SET lease_until = clock_timestamp() - interval '1 millisecond',
                    updated_at = clock_timestamp()
                WHERE resource_key = ? AND owner_id = ? AND fencing_token = ?
                """, claim.jobKey(), claim.workerId(), claim.fencingToken());
        if (changed != 1) {
            throw new LeaseLostException("Could not release lease for jobKey=" + claim.jobKey());
        }
    }

    private Checkpoint loadCheckpoint(String jobKey) {
        List<Checkpoint> rows = jdbc.query("""
                SELECT last_completed_unit, checksum
                FROM demo_checkpoint WHERE job_key = ?
                """, (rs, rowNum) -> new Checkpoint(
                rs.getInt("last_completed_unit"), rs.getLong("checksum")), jobKey);
        return rows.isEmpty() ? new Checkpoint(0, 0) : rows.getFirst();
    }

    private JobRow mapJob(ResultSet rs, int rowNum) throws SQLException {
        return new JobRow(
                rs.getObject("job_id", UUID.class),
                rs.getString("job_key"),
                rs.getInt("total_units"),
                rs.getInt("unit_delay_ms"),
                rs.getInt("checkpoint_every"),
                JobState.valueOf(rs.getString("state")),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getInt("fail_until_attempt"));
    }

    private record JobRow(UUID jobId, String jobKey, int totalUnits, int unitDelayMs,
                          int checkpointEvery, JobState state, int attemptCount,
                          int maxAttempts, int failUntilAttempt) {
    }

    private record Checkpoint(int unit, long checksum) {
    }
}
