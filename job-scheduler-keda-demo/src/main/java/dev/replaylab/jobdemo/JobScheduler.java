package dev.replaylab.jobdemo;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "scheduler")
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final String queueName;
    private final int batchSize;

    public JobScheduler(JdbcTemplate jdbc, TransactionTemplate transactions,
                        RabbitTemplate rabbit,
                        @Value("${app.rabbit.ready-queue}") String queueName,
                        @Value("${app.scheduler.batch-size:50}") int batchSize) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.queueName = queueName;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.poll-delay:1s}")
    public void dispatch() {
        List<UUID> jobIds = transactions.execute(status -> {
            List<UUID> due = jdbc.queryForList("""
                    SELECT job_id FROM demo_job
                    WHERE state = 'SCHEDULED' AND scheduled_at <= clock_timestamp()
                    ORDER BY scheduled_at, job_id
                    FOR UPDATE SKIP LOCKED
                    LIMIT ?
                    """, UUID.class, batchSize);
            due.forEach(id -> jdbc.update("""
                    UPDATE demo_job SET state = 'QUEUED'
                    WHERE job_id = ? AND state = 'SCHEDULED'
                    """, id));
            return due;
        });

        if (jobIds == null) {
            return;
        }
        for (UUID jobId : jobIds) {
            try {
                rabbit.convertAndSend(queueName, jobId.toString());
            } catch (RuntimeException exception) {
                jdbc.update("UPDATE demo_job SET state = 'SCHEDULED' WHERE job_id = ? AND state = 'QUEUED'",
                        jobId);
                throw exception;
            }
        }
        if (!jobIds.isEmpty()) {
            log.info("Queued {} jobs in RabbitMQ", jobIds.size());
        }
    }
}
