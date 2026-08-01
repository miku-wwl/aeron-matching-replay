package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReplayJobs
{
    ReplayJobSnapshot start(ReplayCommand command);

    Optional<ReplayJobSnapshot> find(UUID jobId);

    List<ReplayJobSnapshot> list();
}
