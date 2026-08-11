package dev.replaylab.jobdemo.api;

import dev.replaylab.jobdemo.domain.CreateJobRequest;
import dev.replaylab.jobdemo.domain.JobState;
import dev.replaylab.jobdemo.domain.JobView;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class JobController {

    private final JobService jobs;

    public JobController(JobService jobs) {
        this.jobs = jobs;
    }

    @PostMapping
    public ResponseEntity<JobView> create(@Valid @RequestBody CreateJobRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(jobs.create(request));
    }

    @PostMapping("/burst")
    public ResponseEntity<List<JobView>> burst(
            @RequestParam(defaultValue = "50") int count,
            @RequestParam(defaultValue = "200") int totalUnits,
            @RequestParam(defaultValue = "20") int unitDelayMs,
            @RequestParam(defaultValue = "25") int checkpointEvery,
            @RequestParam(defaultValue = "3") int maxAttempts,
            @RequestParam(defaultValue = "0") int failUntilAttempt) {
        return ResponseEntity.status(HttpStatus.CREATED).body(
                jobs.createBurst(count, totalUnits, unitDelayMs, checkpointEvery,
                        maxAttempts, failUntilAttempt));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<JobView> get(@PathVariable UUID jobId) {
        return jobs.find(jobId).map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<JobView> list(@RequestParam(required = false) JobState state,
                              @RequestParam(defaultValue = "100") int limit) {
        return jobs.list(state, limit);
    }

    @GetMapping("/stats")
    public Map<String, Long> stats() {
        return jobs.stats();
    }
}
