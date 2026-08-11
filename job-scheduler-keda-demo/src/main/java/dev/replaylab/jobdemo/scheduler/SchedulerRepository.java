package dev.replaylab.jobdemo.scheduler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.replaylab.jobdemo.domain.JobMessage;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Repository
public class SchedulerRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper objectMapper;

    public SchedulerRepository(JdbcTemplate jdbc, TransactionTemplate transactions, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.objectMapper = objectMapper;
    }

    public int dispatchDue(int batchSize) {
        Integer count = transactions.execute(status -> {
            List<UUID> jobIds = jdbc.queryForList("""
                    SELECT job_id
                    FROM demo_job
                    WHERE state IN ('SCHEDULED', 'RETRY_WAIT')
                      AND next_run_at <= clock_timestamp()
                    ORDER BY next_run_at, job_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """, UUID.class, batchSize);

            for (UUID jobId : jobIds) {
                UUID outboxId = UUID.randomUUID();
                int changed = jdbc.update("""
                        UPDATE demo_job
                        SET state = 'QUEUED', worker_id = NULL, current_fencing_token = NULL,
                            failure_code = NULL, version = version + 1,
                            updated_at = clock_timestamp()
                        WHERE job_id = ? AND state IN ('SCHEDULED', 'RETRY_WAIT')
                        """, jobId);
                if (changed != 1) {
                    throw new IllegalStateException("Locked due job could not be dispatched: " + jobId);
                }
                jdbc.update("""
                        INSERT INTO demo_outbox(outbox_id, job_id, event_type, payload)
                        VALUES (?, ?, 'JOB_READY', ?::jsonb)
                        """, outboxId, jobId, json(new JobMessage(outboxId, jobId)));
            }
            return jobIds.size();
        });
        return count == null ? 0 : count;
    }

    private String json(JobMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot serialize outbox message", exception);
        }
    }
}
