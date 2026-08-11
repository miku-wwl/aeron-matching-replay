package dev.replaylab.jobdemo;

import dev.replaylab.jobdemo.domain.JobMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "app.role=worker",
        "app.worker.id=integration-worker",
        "app.worker.lease-duration=5s",
        "app.worker.heartbeat-interval=1s"
})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RabbitWorkerIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    RabbitTemplate rabbit;

    @BeforeEach
    void clean() {
        jdbc.execute("TRUNCATE demo_attempt, demo_checkpoint, demo_lease, demo_outbox, demo_job CASCADE");
        new RabbitAdmin(rabbit).purgeQueue("demo.jobs.ready", true);
        new RabbitAdmin(rabbit).purgeQueue("demo.jobs.dlq", true);
    }

    @Test
    void workerConsumesWithManualAckAndSafelyAcksDuplicate() throws Exception {
        UUID jobId = UUID.randomUUID();
        UUID outboxId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO demo_job(
                    job_id, job_key, total_units, unit_delay_ms, checkpoint_every,
                    state, scheduled_at, next_run_at, max_attempts
                ) VALUES (?, ?, 20, 1, 5, 'QUEUED', clock_timestamp(), clock_timestamp(), 3)
                """, jobId, "rabbit-" + jobId);

        rabbit.convertAndSend("demo.jobs.exchange", "jobs.ready", new JobMessage(outboxId, jobId));
        awaitState(jobId, "SUCCEEDED", Duration.ofSeconds(20));

        rabbit.convertAndSend("demo.jobs.exchange", "jobs.ready", new JobMessage(outboxId, jobId));
        Thread.sleep(1_000);

        assertThat(jdbc.queryForObject("SELECT attempt_count FROM demo_job WHERE job_id = ?",
                Integer.class, jobId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_attempt WHERE job_id = ?",
                Long.class, jobId)).isEqualTo(1L);
        assertThat(new RabbitAdmin(rabbit).getQueueInfo("demo.jobs.ready").getMessageCount()).isZero();
    }

    private void awaitState(UUID jobId, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String state = jdbc.queryForObject("SELECT state FROM demo_job WHERE job_id = ?", String.class, jobId);
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for job state " + expected);
    }
}
