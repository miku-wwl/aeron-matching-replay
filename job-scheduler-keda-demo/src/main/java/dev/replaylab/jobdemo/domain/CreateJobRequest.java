package dev.replaylab.jobdemo.domain;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.time.Instant;

public record CreateJobRequest(
        @NotBlank String jobKey,
        @Positive Integer totalUnits,
        @PositiveOrZero Integer unitDelayMs,
        @Positive Integer checkpointEvery,
        Instant scheduledAt,
        @Positive Integer maxAttempts,
        @PositiveOrZero Integer failUntilAttempt) {
}
