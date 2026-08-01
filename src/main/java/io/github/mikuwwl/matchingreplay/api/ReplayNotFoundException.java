package io.github.mikuwwl.matchingreplay.api;

import java.util.UUID;

public class ReplayNotFoundException extends RuntimeException
{
    public ReplayNotFoundException(final UUID jobId)
    {
        super("Replay job not found: " + jobId);
    }
}
