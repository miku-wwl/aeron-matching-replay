package dev.replaylab.jobdemo.scheduler;

import dev.replaylab.jobdemo.domain.JobMessage;
import dev.replaylab.jobdemo.metrics.DemoMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.MessageDeliveryMode;
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
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final RabbitTemplate rabbit;
    private final DemoMetrics metrics;
    private final String exchange;
    private final String routingKey;

    public OutboxPublisher(JdbcTemplate jdbc,
                           TransactionTemplate transactions,
                           RabbitTemplate rabbit,
                           DemoMetrics metrics,
                           @Value("${app.rabbit.exchange}") String exchange,
                           @Value("${app.rabbit.ready-routing-key}") String routingKey) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.rabbit = rabbit;
        this.metrics = metrics;
        this.exchange = exchange;
        this.routingKey = routingKey;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.outbox-delay:250ms}")
    public void publishPending() {
        for (int i = 0; i < 25; i++) {
            Boolean published = transactions.execute(status -> publishOneLocked());
            if (!Boolean.TRUE.equals(published)) {
                return;
            }
        }
    }

    private boolean publishOneLocked() {
        List<OutboxRow> rows = jdbc.query("""
                SELECT outbox_id, job_id
                FROM demo_outbox
                WHERE published_at IS NULL
                ORDER BY created_at
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """, (rs, rowNum) -> new OutboxRow(
                rs.getObject("outbox_id", UUID.class),
                rs.getObject("job_id", UUID.class)));
        if (rows.isEmpty()) {
            return false;
        }

        OutboxRow row = rows.getFirst();
        JobMessage event = new JobMessage(row.outboxId(), row.jobId());
        rabbit.invoke(operations -> {
            operations.convertAndSend(exchange, routingKey, event, message -> {
                message.getMessageProperties().setMessageId(row.outboxId().toString());
                message.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
                return message;
            });
            operations.waitForConfirmsOrDie(5_000);
            return null;
        });

        int marked = jdbc.update("""
                UPDATE demo_outbox SET published_at = clock_timestamp()
                WHERE outbox_id = ? AND published_at IS NULL
                """, row.outboxId());
        if (marked != 1) {
            throw new IllegalStateException("Could not mark outbox event as published: " + row.outboxId());
        }
        metrics.outboxPublished().increment();
        log.info("Published outboxId={} jobId={} with RabbitMQ publisher confirm",
                row.outboxId(), row.jobId());
        return true;
    }

    private record OutboxRow(UUID outboxId, UUID jobId) {
    }
}
