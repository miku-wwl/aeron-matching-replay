package io.github.mikuwwl.matchingreplay.aeron;

public interface ReplayEngine
{
    ReplayResult replay(
        ReplayCommand command,
        ReplayAttempt attempt,
        ReplayProgressListener progressListener);
}
