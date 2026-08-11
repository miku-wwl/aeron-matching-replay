package dev.replaylab.jobdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.replaylab.jobdemo.domain.JobClaim;
import dev.replaylab.jobdemo.metrics.DemoMetrics;
import dev.replaylab.jobdemo.scheduler.SchedulerRepository;
import dev.replaylab.jobdemo.worker.LeaseLostException;
import dev.replaylab.jobdemo.worker.WorkerCoordinator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
class PostgresConcurrencyTest {

    @Container
    private static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    private JdbcTemplate jdbc;
    private WorkerCoordinator workers;
    private TransactionTemplate transactions;

    @BeforeEach
    void setUp() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        workers = new WorkerCoordinator(jdbc, transactions,
                new DemoMetrics(new SimpleMeterRegistry()), Duration.ofSeconds(5));
        jdbc.execute("TRUNCATE demo_attempt, demo_checkpoint, demo_lease, demo_outbox, demo_job CASCADE");
    }

    @Test
    void onlyOneOfTwentyWorkersCanOwnTheLease() throws Exception {
        UUID jobId = insertQueuedJob("lease-race");
        CountDownLatch ready = new CountDownLatch(20);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JobClaim>> results = new ArrayList<>();

        try (ExecutorService executor = Executors.newFixedThreadPool(20)) {
            for (int i = 0; i < 20; i++) {
                String workerId = "worker-" + i;
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return workers.claim(jobId, workerId);
                }));
            }
            ready.await();
            start.countDown();

            long acquired = 0;
            for (Future<JobClaim> result : results) {
                if (result.get().acquired()) {
                    acquired++;
                }
            }
            assertThat(acquired).isEqualTo(1);
        }
    }

    @Test
    void expiredLeaseCanBeTakenOverAndStaleTokenCannotWriteCheckpoint() {
        UUID jobId = insertQueuedJob("fencing");
        JobClaim first = workers.claim(jobId, "worker-a");
        workers.saveCheckpoint(first, 100, 100_100L);

        jdbc.update("UPDATE demo_lease SET lease_until = clock_timestamp() - interval '1 second' "
                + "WHERE resource_key = ?", first.jobKey());
        JobClaim second = workers.claim(jobId, "worker-b");
        workers.saveCheckpoint(second, 200, 200_200L);

        assertThat(second.fencingToken()).isGreaterThan(first.fencingToken());
        assertThat(second.lastCompletedUnit()).isEqualTo(100);
        assertThatThrownBy(() -> workers.saveCheckpoint(first, 150, 150_150L))
                .isInstanceOf(LeaseLostException.class);
        assertThat(jdbc.queryForObject("SELECT last_completed_unit FROM demo_checkpoint WHERE job_key = ?",
                Integer.class, first.jobKey())).isEqualTo(200);
    }

    @Test
    void checkpointCannotMoveBackwardsEvenForCurrentOwner() {
        JobClaim claim = workers.claim(insertQueuedJob("checkpoint-regression"), "worker-a");
        workers.saveCheckpoint(claim, 100, 100L);

        assertThatThrownBy(() -> workers.saveCheckpoint(claim, 99, 99L))
                .isInstanceOf(LeaseLostException.class);
    }

    @Test
    void twoSchedulersCreateExactlyOneOutboxRecordPerDueJob() throws Exception {
        for (int i = 0; i < 100; i++) {
            insertScheduledJob("scheduled-" + i);
        }
        SchedulerRepository first = new SchedulerRepository(jdbc, transactions, new ObjectMapper());
        SchedulerRepository second = new SchedulerRepository(jdbc, transactions, new ObjectMapper());

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<Integer> firstCount = executor.submit(() -> first.dispatchDue(100));
            Future<Integer> secondCount = executor.submit(() -> second.dispatchDue(100));
            assertThat(firstCount.get() + secondCount.get()).isEqualTo(100);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_outbox", Long.class)).isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT job_id) FROM demo_outbox", Long.class))
                .isEqualTo(100L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM demo_job WHERE state = 'QUEUED'", Long.class))
                .isEqualTo(100L);
    }

    private UUID insertQueuedJob(String key) {
        return insertJob(key, "QUEUED");
    }

    private UUID insertScheduledJob(String key) {
        return insertJob(key, "SCHEDULED");
    }

    private UUID insertJob(String key, String state) {
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO demo_job(
                    job_id, job_key, total_units, unit_delay_ms, checkpoint_every,
                    state, scheduled_at, next_run_at, max_attempts
                ) VALUES (?, ?, 1000, 0, 50, ?, clock_timestamp(), clock_timestamp(), 3)
                """, jobId, key, state);
        return jobId;
    }
}
