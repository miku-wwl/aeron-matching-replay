package dev.replaylab.jobdemo;

import java.time.Instant;
import java.util.UUID;

public record JobEvent(UUID eventId, String jobKey, int durationMs, Instant createdAt) {
}
