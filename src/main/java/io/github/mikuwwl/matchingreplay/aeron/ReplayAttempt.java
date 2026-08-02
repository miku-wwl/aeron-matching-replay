package io.github.mikuwwl.matchingreplay.aeron;

import java.util.UUID;

public record ReplayAttempt(UUID jobId, UUID attemptId)
{
    public ReplayAttempt
    {
        if (jobId == null || attemptId == null)
        {
            throw new IllegalArgumentException("jobId and attemptId are required");
        }
    }

    public static ReplayAttempt standalone()
    {
        return new ReplayAttempt(UUID.randomUUID(), UUID.randomUUID());
    }
}
