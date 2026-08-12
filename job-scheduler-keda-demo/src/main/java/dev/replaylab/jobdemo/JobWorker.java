package dev.replaylab.jobdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final String workerId;

    public JobWorker(JdbcTemplate jdbc, TransactionTemplate transactions,
                     @Value("${app.worker.id}") String workerId) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.workerId = workerId;
    }

    @RabbitListener(queues = "${app.rabbit.ready-queue}")
    public void consume(String message) throws InterruptedException {
        UUID jobId = UUID.fromString(message);
        Work work = transactions.execute(status -> claim(jobId));
        if (work == null) {
            log.info("Ignoring duplicate message for jobId={}", jobId);
            return;
        }

        try {
            Thread.sleep(work.durationMs());
            jdbc.update("""
                    UPDATE demo_job
                    SET state = 'SUCCEEDED', completed_at = clock_timestamp()
                    WHERE job_id = ? AND state = 'RUNNING' AND worker_id = ?
                    """, jobId, workerId);
            log.info("Completed jobId={} durationMs={}", jobId, work.durationMs());
        } catch (InterruptedException exception) {
            jdbc.update("""
                    UPDATE demo_job SET state = 'SCHEDULED', worker_id = NULL, started_at = NULL
                    WHERE job_id = ? AND state = 'RUNNING' AND worker_id = ?
                    """, jobId, workerId);
            Thread.currentThread().interrupt();
            throw exception;
        } catch (RuntimeException exception) {
            jdbc.update("""
                    UPDATE demo_job SET state = 'FAILED', completed_at = clock_timestamp()
                    WHERE job_id = ? AND state = 'RUNNING' AND worker_id = ?
                    """, jobId, workerId);
            throw exception;
        }
    }

    private Work claim(UUID jobId) {
        List<Integer> durations = jdbc.query("""
                SELECT duration_ms FROM demo_job
                WHERE job_id = ? AND state = 'QUEUED'
                FOR UPDATE
                """, (rs, rowNum) -> rs.getInt(1), jobId);
        if (durations.isEmpty()) {
            return null;
        }
        int changed = jdbc.update("""
                UPDATE demo_job
                SET state = 'RUNNING', worker_id = ?, started_at = clock_timestamp()
                WHERE job_id = ? AND state = 'QUEUED'
                """, workerId, jobId);
        return changed == 1 ? new Work(durations.getFirst()) : null;
    }

    private record Work(int durationMs) {
    }
}
