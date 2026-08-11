package dev.replaylab.jobdemo.domain;

import java.util.UUID;

public record JobClaim(
        Outcome outcome,
        UUID jobId,
        String jobKey,
        int totalUnits,
        int unitDelayMs,
        int checkpointEvery,
        int attemptCount,
        int maxAttempts,
        int failUntilAttempt,
        UUID attemptId,
        String workerId,
        long fencingToken,
        int lastCompletedUnit,
        long checksum) {

    public enum Outcome {
        ACQUIRED,
        BUSY,
        DUPLICATE_OR_TERMINAL,
        NOT_FOUND
    }

    public static JobClaim outcome(Outcome outcome, UUID jobId) {
        return new JobClaim(outcome, jobId, null, 0, 0, 0, 0, 0, 0,
                null, null, 0, 0, 0);
    }

    public boolean acquired() {
        return outcome == Outcome.ACQUIRED;
    }
}
