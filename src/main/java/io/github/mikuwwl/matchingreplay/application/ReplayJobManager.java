package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayEngine;
import io.github.mikuwwl.matchingreplay.aeron.ReplayAttempt;
import io.github.mikuwwl.matchingreplay.aeron.ReplayProgress;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.github.mikuwwl.matchingreplay.observability.ReplayMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ReplayJobManager implements ReplayJobs
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ReplayJobManager.class);

    private final ReplayEngine replayEngine;
    private final TaskExecutor taskExecutor;
    private final Clock clock;
    private final ReplayMetrics metrics;
    private final ConcurrentHashMap<UUID, ReplayJobSnapshot> jobs = new ConcurrentHashMap<>();
    private final Set<String> activeCheckpointKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReplayJobManager(
        final ReplayEngine replayEngine,
        @Qualifier("replayTaskExecutor") final TaskExecutor taskExecutor,
        final ReplayMetrics metrics)
    {
        this(replayEngine, taskExecutor, Clock.systemUTC(), metrics);
    }

    ReplayJobManager(
        final ReplayEngine replayEngine,
        final TaskExecutor taskExecutor,
        final Clock clock)
    {
        this(replayEngine, taskExecutor, clock, ReplayMetrics.noop());
    }

    ReplayJobManager(
        final ReplayEngine replayEngine,
        final TaskExecutor taskExecutor,
        final Clock clock,
        final ReplayMetrics metrics)
    {
        this.replayEngine = replayEngine;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
        this.metrics = metrics;
    }

    @Override
    public ReplayJobSnapshot start(final ReplayCommand command)
    {
        if (!activeCheckpointKeys.add(command.checkpointKey()))
        {
            throw new ReplayConflictException(
                "A replay is already active for checkpointKey=" + command.checkpointKey());
        }

        final UUID jobId = UUID.randomUUID();
        final UUID attemptId = UUID.randomUUID();
        final ReplayJobSnapshot queued = new ReplayJobSnapshot(
            jobId,
            attemptId,
            command,
            ReplayJobState.QUEUED,
            clock.instant(),
            null,
            null,
            null,
            null,
            null);
        jobs.put(jobId, queued);
        logAccepted(queued);
        try
        {
            taskExecutor.execute(() -> run(jobId));
            return jobs.get(jobId);
        }
        catch (final RuntimeException ex)
        {
            activeCheckpointKeys.remove(command.checkpointKey());
            jobs.remove(jobId);
            throw new ReplayCapacityException("Replay worker queue is full", ex);
        }
    }

    @Override
    public Optional<ReplayJobSnapshot> find(final UUID jobId)
    {
        return Optional.ofNullable(jobs.get(jobId));
    }

    @Override
    public List<ReplayJobSnapshot> list()
    {
        return jobs.values().stream()
            .sorted(Comparator.comparing(ReplayJobSnapshot::acceptedAt).reversed())
            .toList();
    }

    private void run(final UUID jobId)
    {
        final ReplayJobSnapshot queued = jobs.get(jobId);
        if (queued == null)
        {
            return;
        }

        final ReplayJobSnapshot running = new ReplayJobSnapshot(
            jobId,
            queued.attemptId(),
            queued.command(),
            ReplayJobState.RUNNING,
            queued.acceptedAt(),
            clock.instant(),
            null,
            null,
            null,
            null);
        jobs.put(jobId, running);

        try (MDC.MDCCloseable ignoredJob = MDC.putCloseable(
                "jobId",
                jobId.toString());
            MDC.MDCCloseable ignoredAttempt = MDC.putCloseable(
                "attemptId",
                queued.attemptId().toString());
            MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable(
                "correlationId",
                nullToEmpty(queued.command().correlationId()));
            MDC.MDCCloseable ignoredRecording = MDC.putCloseable(
                "recordingId",
                Long.toString(queued.command().recordingId())))
        {
            LOGGER.info(
                "REPLAY_STARTED jobId={} attemptId={} correlationId={} " +
                    "recordingId={} checkpointKey={}",
                jobId,
                queued.attemptId(),
                queued.command().correlationId(),
                queued.command().recordingId(),
                queued.command().checkpointKey());
            try
            {
                final ReplayResult result = replayEngine.replay(
                    queued.command(),
                    new ReplayAttempt(jobId, queued.attemptId()),
                    progress -> updateProgress(jobId, progress));
                complete(jobId, queued, result);
            }
            catch (final RuntimeException ex)
            {
                fail(jobId, queued, ex);
            }
            finally
            {
                activeCheckpointKeys.remove(queued.command().checkpointKey());
                metrics.clearPositionLag(jobId);
            }
        }
    }

    private void complete(
        final UUID jobId,
        final ReplayJobSnapshot queued,
        final ReplayResult result)
    {
        if (!jobId.equals(result.jobId()) ||
            !queued.attemptId().equals(result.attemptId()))
        {
            throw new IllegalStateException(
                "Replay result identity does not match active job/attempt");
        }
        final ReplayJobState state = result.verificationPassed() ?
            ReplayJobState.VERIFIED : ReplayJobState.VERIFICATION_FAILED;
        final ReplayFailure failure = result.verificationPassed() ?
            null : ReplayFailure.verificationMismatch(
                result.recordingId(),
                result.replayStopPosition(),
                result.replayStopPosition(),
                result.expectedLastEventSequence(),
                result.finalEventSequence(),
                result.expectedReplayDigest(),
                result.finalReplayDigest());
        final ReplayJobSnapshot current = jobs.get(jobId);
        final ReplayJobSnapshot terminal = new ReplayJobSnapshot(
            jobId,
            queued.attemptId(),
            queued.command(),
            state,
            queued.acceptedAt(),
            current.startedAt(),
            clock.instant(),
            current.progress(),
            result,
            failure);
        jobs.put(jobId, terminal);
        metrics.recordTerminal(
            state.name().toLowerCase(),
            elapsed(terminal),
            result,
            terminal.progress(),
            failure);
        if (result.verificationPassed())
        {
            LOGGER.info(
                "REPLAY_VERIFIED jobId={} attemptId={} correlationId={} recordingId={} " +
                    "replayStartPosition={} replayStopPosition={} finalEventSequence={} " +
                    "finalReplayDigest={} durationMs={}",
                jobId,
                queued.attemptId(),
                queued.command().correlationId(),
                queued.command().recordingId(),
                result.replayStartPosition(),
                result.replayStopPosition(),
                result.finalEventSequence(),
                Long.toUnsignedString(result.finalReplayDigest()),
                result.replayDurationMs());
        }
        else
        {
            LOGGER.warn(
                "REPLAY_VERIFICATION_FAILED jobId={} attemptId={} correlationId={} " +
                    "recordingId={} " +
                    "expectedLastEventSequence={} actualLastEventSequence={} " +
                    "expectedReplayDigest={} actualReplayDigest={}",
                jobId,
                queued.attemptId(),
                queued.command().correlationId(),
                queued.command().recordingId(),
                result.expectedLastEventSequence(),
                result.finalEventSequence(),
                Long.toUnsignedString(result.expectedReplayDigest()),
                Long.toUnsignedString(result.finalReplayDigest()));
        }
    }

    private void fail(
        final UUID jobId,
        final ReplayJobSnapshot queued,
        final RuntimeException ex)
    {
        final ReplayJobSnapshot current = jobs.get(jobId);
        final ReplayFailure failure = failureFrom(
            ex,
            queued.command(),
            current.progress());
        final ReplayJobSnapshot terminal = new ReplayJobSnapshot(
            jobId,
            queued.attemptId(),
            queued.command(),
            ReplayJobState.FAILED,
            queued.acceptedAt(),
            current.startedAt(),
            clock.instant(),
            current.progress(),
            null,
            failure);
        jobs.put(jobId, terminal);
        metrics.recordTerminal(
            ReplayJobState.FAILED.name().toLowerCase(),
            elapsed(terminal),
            null,
            terminal.progress(),
            failure);
        LOGGER.error(
            "REPLAY_FAILED jobId={} attemptId={} correlationId={} recordingId={} " +
                "failureCode={} " +
                "currentPosition={} lastEventSequence={} message={}",
            jobId,
            queued.attemptId(),
            queued.command().correlationId(),
            queued.command().recordingId(),
            failure.code(),
            failure.currentPosition(),
            failure.lastAppliedEventSequence(),
            failure.message(),
            ex);
    }

    private void updateProgress(final UUID jobId, final ReplayProgress progress)
    {
        jobs.computeIfPresent(jobId, (ignored, current) ->
        {
            final ReplayProgress previous = current.progress();
            if (previous == null && progress.lastEventSequence() > 0)
            {
                LOGGER.info(
                    "REPLAY_RESUMED jobId={} correlationId={} recordingId={} " +
                        "replayStartPosition={} lastEventSequence={}",
                    jobId,
                    current.command().correlationId(),
                    current.command().recordingId(),
                    progress.replayStartPosition(),
                    progress.lastEventSequence());
            }
            if (previous != null &&
                progress.lastCheckpointPosition() >
                    previous.lastCheckpointPosition())
            {
                LOGGER.info(
                    "REPLAY_CHECKPOINTED jobId={} correlationId={} recordingId={} " +
                        "checkpointPosition={} lastEventSequence={} " +
                        "appliedEventsThisRun={} duplicatesThisRun={}",
                    jobId,
                    current.command().correlationId(),
                    current.command().recordingId(),
                    progress.lastCheckpointPosition(),
                    progress.lastEventSequence(),
                    progress.appliedEventsThisRun(),
                    progress.duplicatesThisRun());
            }
            if (progress.progressPercent() == 100.0 &&
                (previous == null || previous.progressPercent() < 100.0))
            {
                LOGGER.info(
                    "REPLAY_BOUNDARY_REACHED jobId={} correlationId={} recordingId={} " +
                        "currentPosition={} replayStopPosition={} lastEventSequence={}",
                    jobId,
                    current.command().correlationId(),
                    current.command().recordingId(),
                    progress.currentPosition(),
                    progress.replayStopPosition(),
                    progress.lastEventSequence());
            }
            metrics.updatePositionLag(
                jobId,
                progress.replayStopPosition() - progress.currentPosition());
            return current.withProgress(progress);
        });
    }

    private void logAccepted(final ReplayJobSnapshot queued)
    {
        try (MDC.MDCCloseable ignoredJob = MDC.putCloseable(
                "jobId",
                queued.jobId().toString());
            MDC.MDCCloseable ignoredAttempt = MDC.putCloseable(
                "attemptId",
                queued.attemptId().toString());
            MDC.MDCCloseable ignoredCorrelation = MDC.putCloseable(
                "correlationId",
                nullToEmpty(queued.command().correlationId()));
            MDC.MDCCloseable ignoredRecording = MDC.putCloseable(
                "recordingId",
                Long.toString(queued.command().recordingId())))
        {
            LOGGER.info(
                "REPLAY_REQUEST_ACCEPTED jobId={} attemptId={} correlationId={} recordingId={} " +
                    "checkpointKey={} requestedStopPosition={}",
                queued.jobId(),
                queued.attemptId(),
                queued.command().correlationId(),
                queued.command().recordingId(),
                queued.command().checkpointKey(),
                queued.command().stopPosition());
        }
    }

    private static ReplayFailure failureFrom(
        final RuntimeException ex,
        final ReplayCommand command,
        final ReplayProgress progress)
    {
        if (ex instanceof ReplayException replayException)
        {
            return replayException.failure();
        }
        final long currentPosition = progress == null ? 0 : progress.currentPosition();
        final long replayStop = progress == null ?
            (command.stopPosition() == null ? 0 : command.stopPosition()) :
            progress.replayStopPosition();
        final long lastSequence = progress == null ? 0 : progress.lastEventSequence();
        return ReplayFailure.basic(
                ReplayFailureCode.INTERNAL_ERROR,
                safeMessage(ex))
            .withReplayContext(
                command.recordingId(),
                currentPosition,
                replayStop,
                lastSequence);
    }

    private static Duration elapsed(final ReplayJobSnapshot snapshot)
    {
        return Duration.between(snapshot.startedAt(), snapshot.completedAt());
    }

    private static String safeMessage(final RuntimeException ex)
    {
        return ex.getMessage() == null || ex.getMessage().isBlank() ?
            ex.getClass().getSimpleName() : ex.getMessage();
    }

    private static String nullToEmpty(final String value)
    {
        return value == null ? "" : value;
    }
}
