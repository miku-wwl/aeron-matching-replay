package dev.replaylab.jobdemo;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {"app.role=worker", "app.worker.id=test-worker"})
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class CoreFlowIntegrationTest {

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

    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired RabbitTemplate rabbit;

    @Test
    void schedulerQueuesAndWorkerCompletesAJob() throws Exception {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO demo_job(job_id, job_key, duration_ms, state, scheduled_at)
                VALUES (?, ?, 10, 'SCHEDULED', clock_timestamp())
                """, jobId, "core-flow-" + jobId);

        new JobScheduler(jdbc, transactions, rabbit, "demo.jobs.ready", 50).dispatch();

        awaitState(jobId, "SUCCEEDED", Duration.ofSeconds(20));
        assertThat(jdbc.queryForObject(
                "SELECT worker_id FROM demo_job WHERE job_id = ?", String.class, jobId))
                .isEqualTo("test-worker");
    }

    private void awaitState(UUID jobId, String expected, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            String state = jdbc.queryForObject(
                    "SELECT state FROM demo_job WHERE job_id = ?", String.class, jobId);
            if (expected.equals(state)) {
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("Timed out waiting for state " + expected);
    }
}
