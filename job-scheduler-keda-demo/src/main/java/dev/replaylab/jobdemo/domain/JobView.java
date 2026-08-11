package dev.replaylab.jobdemo.domain;

import java.time.Instant;
import java.util.UUID;

public record JobView(
        UUID jobId,
        String jobKey,
        int totalUnits,
        int unitDelayMs,
        int checkpointEvery,
        JobState state,
        Instant scheduledAt,
        Instant nextRunAt,
        int attemptCount,
        int maxAttempts,
        int failUntilAttempt,
        String workerId,
        Long fencingToken,
        String failureCode,
        Integer lastCompletedUnit,
        Long checksum,
        Instant createdAt,
        Instant updatedAt,
        Instant completedAt) {
}
