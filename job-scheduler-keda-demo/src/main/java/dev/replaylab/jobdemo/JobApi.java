package dev.replaylab.jobdemo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class JobApi {

    private static final Set<String> STATES = Set.of(
            "SCHEDULED", "QUEUED", "RUNNING", "SUCCEEDED", "FAILED");
    private static final String SELECT = """
            SELECT job_id, job_key, duration_ms, state, scheduled_at, worker_id,
                   created_at, started_at, completed_at
            FROM demo_job
            """;

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JobApi(JdbcTemplate jdbc, TransactionTemplate transactions) {
        this.jdbc = jdbc;
        this.transactions = transactions;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public JobView create(@Valid @RequestBody CreateJobRequest request) {
        int durationMs = request.durationMs() == null ? 1_000 : request.durationMs();
        Instant scheduledAt = request.scheduledAt() == null ? Instant.now() : request.scheduledAt();
        UUID jobId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO demo_job(job_id, job_key, duration_ms, state, scheduled_at)
                VALUES (?, ?, ?, 'SCHEDULED', ?)
                """, jobId, request.jobKey(), durationMs, Timestamp.from(scheduledAt));
        return findOne(jobId);
    }

    @PostMapping("/burst")
    @ResponseStatus(HttpStatus.CREATED)
    public BurstResult burst(@RequestParam(defaultValue = "100") int count,
                             @RequestParam(defaultValue = "1000") int durationMs) {
        if (count < 1 || count > 500 || durationMs < 1 || durationMs > 60_000) {
            throw new IllegalArgumentException("count must be 1..500 and durationMs 1..60000");
        }
        String prefix = "burst-" + UUID.randomUUID().toString().substring(0, 8);
        transactions.executeWithoutResult(status -> {
            for (int i = 1; i <= count; i++) {
                jdbc.update("""
                        INSERT INTO demo_job(job_id, job_key, duration_ms, state, scheduled_at)
                        VALUES (?, ?, ?, 'SCHEDULED', clock_timestamp())
                        """, UUID.randomUUID(), prefix + "-" + i, durationMs);
            }
        });
        return new BurstResult(prefix, count, durationMs);
    }

    @GetMapping("/{jobId}")
    public JobView get(@PathVariable UUID jobId) {
        return findOne(jobId);
    }

    @GetMapping
    public List<JobView> list(@RequestParam(required = false) String state,
                              @RequestParam(defaultValue = "100") int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        if (state == null) {
            return jdbc.query(SELECT + " ORDER BY created_at DESC LIMIT ?", this::map, safeLimit);
        }
        String normalized = state.toUpperCase();
        if (!STATES.contains(normalized)) {
            throw new IllegalArgumentException("unknown state: " + state);
        }
        return jdbc.query(SELECT + " WHERE state = ? ORDER BY created_at DESC LIMIT ?",
                this::map, normalized, safeLimit);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        Map<String, Long> result = new LinkedHashMap<>();
        jdbc.query("SELECT state, count(*) FROM demo_job GROUP BY state ORDER BY state", rs -> {
            result.put(rs.getString(1), rs.getLong(2));
        });
        return result;
    }

    @ExceptionHandler({IllegalArgumentException.class, DataIntegrityViolationException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> badRequest(Exception exception) {
        return Map.of("error", exception.getMessage());
    }

    private JobView findOne(UUID jobId) {
        List<JobView> rows = jdbc.query(SELECT + " WHERE job_id = ?", this::map, jobId);
        if (rows.isEmpty()) {
            throw new IllegalArgumentException("job not found: " + jobId);
        }
        return rows.getFirst();
    }

    private JobView map(ResultSet rs, int rowNum) throws SQLException {
        return new JobView(rs.getObject("job_id", UUID.class), rs.getString("job_key"),
                rs.getInt("duration_ms"), rs.getString("state"),
                rs.getTimestamp("scheduled_at").toInstant(), rs.getString("worker_id"),
                rs.getTimestamp("created_at").toInstant(), instant(rs, "started_at"),
                instant(rs, "completed_at"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    public record CreateJobRequest(@NotBlank String jobKey, @Positive Integer durationMs,
                                   Instant scheduledAt) {
    }

    public record BurstResult(String prefix, int count, int durationMs) {
    }

    public record JobView(UUID jobId, String jobKey, int durationMs, String state,
                          Instant scheduledAt, String workerId, Instant createdAt,
                          Instant startedAt, Instant completedAt) {
    }
}
