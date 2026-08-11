package dev.replaylab.jobdemo.domain;

import java.util.UUID;

public record JobMessage(int messageVersion, UUID outboxId, UUID jobId) {

    public JobMessage(UUID outboxId, UUID jobId) {
        this(1, outboxId, jobId);
    }
}
