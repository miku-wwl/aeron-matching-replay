package io.github.mikuwwl.matchingreplay.failure;

public class ReplayException extends RuntimeException
{
    private final ReplayFailure failure;

    public ReplayException(final ReplayFailure failure)
    {
        super(failure.message());
        this.failure = failure;
    }

    public ReplayException(final ReplayFailure failure, final Throwable cause)
    {
        super(failure.message(), cause);
        this.failure = failure;
    }

    public ReplayFailure failure()
    {
        return failure;
    }

    public ReplayException withReplayContext(
        final long recordingId,
        final long currentPosition,
        final long replayStopPosition,
        final long lastEventSequence)
    {
        return new ReplayException(
            failure.withReplayContext(
                recordingId,
                currentPosition,
                replayStopPosition,
                lastEventSequence),
            this);
    }
}
