package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;

import java.time.Instant;
import java.util.UUID;

public record ReplayJobSnapshot(
    UUID jobId,
    UUID attemptId,
    ReplayCommand command,
    ReplayJobState state,
    Instant acceptedAt,
    Instant startedAt,
    Instant completedAt,
    ReplayProgress progress,
    ReplayResult result,
    ReplayFailure failure)
{
    public ReplayJobSnapshot withProgress(final ReplayProgress newProgress)
    {
        return new ReplayJobSnapshot(
            jobId,
            attemptId,
            command,
            state,
            acceptedAt,
            startedAt,
            completedAt,
            newProgress,
            result,
            failure);
    }
}
