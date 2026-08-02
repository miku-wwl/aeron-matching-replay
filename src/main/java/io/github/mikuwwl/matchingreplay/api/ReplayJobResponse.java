package io.github.mikuwwl.matchingreplay.api;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.application.ReplayJobSnapshot;
import io.github.mikuwwl.matchingreplay.application.ReplayJobState;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;

import java.time.Instant;
import java.util.UUID;

public record ReplayJobResponse(
    UUID jobId,
    ReplayJobState state,
    Command command,
    Instant acceptedAt,
    Instant startedAt,
    Instant completedAt,
    Progress progress,
    Result result,
    ReplayFailure failure)
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
            snapshot.progress() == null ? null : Progress.from(snapshot.progress()),
            snapshot.result() == null ? null : Result.from(snapshot.result()),
            snapshot.failure());
    }

    public record Command(
        long recordingId,
        String checkpointKey,
        Long stopPosition,
        long expectedLastEventSequence,
        String expectedReplayDigest,
        String correlationId)
    {
        static Command from(final ReplayCommand command)
        {
            return new Command(
                command.recordingId(),
                command.checkpointKey(),
                command.stopPosition(),
                command.expectedLastEventSequence(),
                Long.toUnsignedString(command.expectedReplayDigest()),
                command.correlationId());
        }
    }

    public record Progress(
        long replayStartPosition,
        long currentPosition,
        long replayStopPosition,
        double progressPercent,
        long lastEventSequence,
        long appliedEventsThisRun,
        long duplicatesThisRun,
        long lastCheckpointPosition,
        long eventsPerSecond,
        Instant lastProgressAt)
    {
        static Progress from(final ReplayProgress progress)
        {
            return new Progress(
                progress.replayStartPosition(),
                progress.currentPosition(),
                progress.replayStopPosition(),
                progress.progressPercent(),
                progress.lastEventSequence(),
                progress.appliedEventsThisRun(),
                progress.duplicatesThisRun(),
                progress.lastCheckpointPosition(),
                progress.eventsPerSecond(),
                progress.lastProgressAt());
        }
    }

    public record Result(
        long recordingId,
        String checkpointKey,
        long replayStartPosition,
        long replayStopPosition,
        long firstAppliedEventSequenceThisRun,
        long lastAppliedEventSequenceThisRun,
        long finalEventSequence,
        long expectedLastEventSequence,
        long appliedEventsThisRun,
        long appliedEventsTotal,
        long duplicatesThisRun,
        long duplicatesTotal,
        long sequenceGapsThisRun,
        String finalReplayDigest,
        String expectedReplayDigest,
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
                result.firstAppliedEventSequenceThisRun(),
                result.lastAppliedEventSequenceThisRun(),
                result.finalEventSequence(),
                result.expectedLastEventSequence(),
                result.appliedEventsThisRun(),
                result.appliedEventsTotal(),
                result.duplicatesThisRun(),
                result.duplicatesTotal(),
                result.sequenceGapsThisRun(),
                Long.toUnsignedString(result.finalReplayDigest()),
                Long.toUnsignedString(result.expectedReplayDigest()),
                result.replayDurationMs(),
                result.verificationPassed());
        }
    }
}
