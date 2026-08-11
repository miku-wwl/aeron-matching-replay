package dev.replaylab.jobdemo.metrics;

import dev.replaylab.jobdemo.domain.JobState;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class DatabaseMetrics {

    public DatabaseMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        for (JobState state : JobState.values()) {
            Gauge.builder("demo.jobs.total", jdbc, ignored -> countJobs(jdbc, state))
                    .tag("state", state.name())
                    .description("Current jobs by database state")
                    .register(registry);
        }
        Gauge.builder("demo.jobs.running", jdbc, ignored -> countJobs(jdbc, JobState.RUNNING))
                .description("Current running jobs")
                .register(registry);
        Gauge.builder("demo.outbox.pending", jdbc, DatabaseMetrics::countPendingOutbox)
                .description("Committed outbox rows waiting for RabbitMQ publisher confirm")
                .register(registry);
    }

    private static double countJobs(JdbcTemplate jdbc, JobState state) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM demo_job WHERE state = ?", Long.class, state.name());
        return count == null ? Double.NaN : count.doubleValue();
    }

    private static double countPendingOutbox(JdbcTemplate jdbc) {
        Long count = jdbc.queryForObject(
                "SELECT count(*) FROM demo_outbox WHERE published_at IS NULL", Long.class);
        return count == null ? Double.NaN : count.doubleValue();
    }
}
