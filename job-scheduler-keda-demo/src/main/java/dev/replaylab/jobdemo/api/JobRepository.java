package dev.replaylab.jobdemo.api;

import dev.replaylab.jobdemo.domain.CreateJobRequest;
import dev.replaylab.jobdemo.domain.JobState;
import dev.replaylab.jobdemo.domain.JobView;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class JobRepository {

    private static final String VIEW_SELECT = """
            SELECT j.job_id, j.job_key, j.total_units, j.unit_delay_ms, j.checkpoint_every,
                   j.state, j.scheduled_at, j.next_run_at, j.attempt_count, j.max_attempts,
                   j.fail_until_attempt, j.worker_id, j.current_fencing_token, j.failure_code,
                   c.last_completed_unit, c.checksum, j.created_at, j.updated_at, j.completed_at
            FROM demo_job j
            LEFT JOIN demo_checkpoint c ON c.job_key = j.job_key
            """;

    private final JdbcTemplate jdbc;

    public JobRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public JobView create(UUID jobId, CreateJobRequest request) {
        Instant scheduledAt = request.scheduledAt();
        jdbc.update("""
                        INSERT INTO demo_job (
                            job_id, job_key, total_units, unit_delay_ms, checkpoint_every,
                            state, scheduled_at, next_run_at, max_attempts, fail_until_attempt
                        ) VALUES (?, ?, ?, ?, ?, 'SCHEDULED', ?, ?, ?, ?)
                        """,
                jobId, request.jobKey(), request.totalUnits(), request.unitDelayMs(),
                request.checkpointEvery(), Timestamp.from(scheduledAt), Timestamp.from(scheduledAt),
                request.maxAttempts(), request.failUntilAttempt());
        return find(jobId).orElseThrow();
    }

    public Optional<JobView> find(UUID jobId) {
        List<JobView> rows = jdbc.query(VIEW_SELECT + " WHERE j.job_id = ?", this::mapView, jobId);
        return rows.stream().findFirst();
    }

    public List<JobView> list(JobState state, int limit) {
        if (state == null) {
            return jdbc.query(VIEW_SELECT + " ORDER BY j.created_at DESC LIMIT ?", this::mapView, limit);
        }
        return jdbc.query(VIEW_SELECT + " WHERE j.state = ? ORDER BY j.created_at DESC LIMIT ?",
                this::mapView, state.name(), limit);
    }

    public Map<String, Long> countsByState() {
        return jdbc.query("SELECT state, count(*) AS count FROM demo_job GROUP BY state",
                rs -> {
                    java.util.LinkedHashMap<String, Long> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) {
                        result.put(rs.getString("state"), rs.getLong("count"));
                    }
                    return result;
                });
    }

    private JobView mapView(ResultSet rs, int rowNum) throws SQLException {
        return new JobView(
                rs.getObject("job_id", UUID.class),
                rs.getString("job_key"),
                rs.getInt("total_units"),
                rs.getInt("unit_delay_ms"),
                rs.getInt("checkpoint_every"),
                JobState.valueOf(rs.getString("state")),
                instant(rs, "scheduled_at"),
                instant(rs, "next_run_at"),
                rs.getInt("attempt_count"),
                rs.getInt("max_attempts"),
                rs.getInt("fail_until_attempt"),
                rs.getString("worker_id"),
                nullableLong(rs, "current_fencing_token"),
                rs.getString("failure_code"),
                nullableInteger(rs, "last_completed_unit"),
                nullableLong(rs, "checksum"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"),
                nullableInstant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInteger(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }
}
