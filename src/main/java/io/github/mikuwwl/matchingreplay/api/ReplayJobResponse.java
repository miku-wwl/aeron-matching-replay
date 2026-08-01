package io.github.mikuwwl.matchingreplay.api;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.application.ReplayJobSnapshot;
import io.github.mikuwwl.matchingreplay.application.ReplayJobState;

import java.time.Instant;
import java.util.UUID;

public record ReplayJobResponse(
    UUID jobId,
    ReplayJobState state,
    Command command,
    Instant acceptedAt,
    Instant startedAt,
    Instant completedAt,
    Result result,
    String error)
{
    public static ReplayJobResponse from(final ReplayJobSnapshot snapshot)
    {
        return new ReplayJobResponse(
            snapshot.jobId(),
            snapshot.state(),
            Command.from(snapshot.command()),
            snapshot.acceptedAt(),
            snapshot.startedAt(),
            snapshot.completedAt(),
            snapshot.result() == null ? null : Result.from(snapshot.result()),
            snapshot.error());
    }

    public record Command(
        long recordingId,
        String checkpointKey,
        Long stopPosition,
        Long expectedLastEventSequence,
        String expectedStateHash,
        String correlationId)
    {
        static Command from(final ReplayCommand command)
        {
            return new Command(
                command.recordingId(),
                command.checkpointKey(),
                command.stopPosition(),
                command.expectedLastEventSequence(),
                command.expectedStateHash() == null ?
                    null : Long.toUnsignedString(command.expectedStateHash()),
                command.correlationId());
        }
    }

    public record Result(
        long recordingId,
        String checkpointKey,
        long replayStartPosition,
        long replayStopPosition,
        long firstRecoveredSequence,
        long lastRecoveredSequence,
        long finalSequence,
        long appliedEvents,
        long gaps,
        long duplicates,
        String stateHash,
        long replayDurationMs,
        boolean verificationPassed)
    {
        static Result from(final ReplayResult result)
        {
            return new Result(
                result.recordingId(),
                result.checkpointKey(),
                result.replayStartPosition(),
                result.replayStopPosition(),
                result.firstRecoveredSequence(),
                result.lastRecoveredSequence(),
                result.finalSequence(),
                result.appliedEvents(),
                result.gaps(),
                result.duplicates(),
                Long.toUnsignedString(result.stateHash()),
                result.replayDurationMs(),
                result.verificationPassed());
        }
    }
}
