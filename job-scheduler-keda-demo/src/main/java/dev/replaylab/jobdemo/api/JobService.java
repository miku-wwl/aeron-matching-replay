package dev.replaylab.jobdemo.api;

import dev.replaylab.jobdemo.domain.CreateJobRequest;
import dev.replaylab.jobdemo.domain.JobState;
import dev.replaylab.jobdemo.domain.JobView;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class JobService {

    private final JobRepository jobs;
    private final TransactionTemplate transactions;

    public JobService(JobRepository jobs, TransactionTemplate transactions) {
        this.jobs = jobs;
        this.transactions = transactions;
    }

    public JobView create(CreateJobRequest input) {
        CreateJobRequest request = withDefaults(input);
        return transactions.execute(status -> jobs.create(UUID.randomUUID(), request));
    }

    public List<JobView> createBurst(int count, int totalUnits, int unitDelayMs,
                                     int checkpointEvery, int maxAttempts, int failUntilAttempt) {
        if (count < 1 || count > 500) {
            throw new IllegalArgumentException("count must be between 1 and 500");
        }
        if (totalUnits < 1 || unitDelayMs < 0 || checkpointEvery < 1 || maxAttempts < 1
                || failUntilAttempt < 0) {
            throw new IllegalArgumentException("invalid burst job parameters");
        }
        String burstId = UUID.randomUUID().toString().substring(0, 8);
        Instant now = Instant.now();
        return transactions.execute(status -> {
            List<JobView> created = new ArrayList<>(count);
            for (int i = 1; i <= count; i++) {
                CreateJobRequest request = new CreateJobRequest(
                        "burst-" + burstId + "-" + i,
                        totalUnits, unitDelayMs, checkpointEvery, now, maxAttempts, failUntilAttempt);
                created.add(jobs.create(UUID.randomUUID(), request));
            }
            return created;
        });
    }

    public Optional<JobView> find(UUID jobId) {
        return jobs.find(jobId);
    }

    public List<JobView> list(JobState state, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return jobs.list(state, safeLimit);
    }

    public Map<String, Long> stats() {
        return jobs.countsByState();
    }

    private static CreateJobRequest withDefaults(CreateJobRequest input) {
        return new CreateJobRequest(
                input.jobKey(),
                input.totalUnits() == null ? 1_000 : input.totalUnits(),
                input.unitDelayMs() == null ? 20 : input.unitDelayMs(),
                input.checkpointEvery() == null ? 50 : input.checkpointEvery(),
                input.scheduledAt() == null ? Instant.now() : input.scheduledAt(),
                input.maxAttempts() == null ? 3 : input.maxAttempts(),
                input.failUntilAttempt() == null ? 0 : input.failUntilAttempt());
    }
}
