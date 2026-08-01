package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;

import java.time.Instant;
import java.util.UUID;

public record ReplayJobSnapshot(
    UUID jobId,
    ReplayCommand command,
    ReplayJobState state,
    Instant acceptedAt,
    Instant startedAt,
    Instant completedAt,
    ReplayResult result,
    String error)
{
}
