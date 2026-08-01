package io.github.mikuwwl.matchingreplay.application;

import io.github.mikuwwl.matchingreplay.aeron.AeronReplayCoordinator;
import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import io.github.mikuwwl.matchingreplay.aeron.ReplayResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
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

    private final AeronReplayCoordinator coordinator;
    private final TaskExecutor taskExecutor;
    private final Clock clock;
    private final ConcurrentHashMap<UUID, ReplayJobSnapshot> jobs = new ConcurrentHashMap<>();
    private final Set<String> activeCheckpointKeys = ConcurrentHashMap.newKeySet();

    @Autowired
    public ReplayJobManager(
        final AeronReplayCoordinator coordinator,
        @Qualifier("replayTaskExecutor") final TaskExecutor taskExecutor)
    {
        this(coordinator, taskExecutor, Clock.systemUTC());
    }

    ReplayJobManager(
        final AeronReplayCoordinator coordinator,
        final TaskExecutor taskExecutor,
        final Clock clock)
    {
        this.coordinator = coordinator;
        this.taskExecutor = taskExecutor;
        this.clock = clock;
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
        final ReplayJobSnapshot queued = new ReplayJobSnapshot(
            jobId,
            command,
            ReplayJobState.QUEUED,
            clock.instant(),
            null,
            null,
            null,
            null);
        jobs.put(jobId, queued);
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

        jobs.put(jobId, new ReplayJobSnapshot(
            jobId,
            queued.command(),
            ReplayJobState.RUNNING,
            queued.acceptedAt(),
            clock.instant(),
            null,
            null,
            null));
        try
        {
            final ReplayResult result = coordinator.replay(queued.command());
            final ReplayJobState state = result.verificationPassed() ?
                ReplayJobState.SUCCEEDED : ReplayJobState.VERIFICATION_FAILED;
            final ReplayJobSnapshot running = jobs.get(jobId);
            jobs.put(jobId, new ReplayJobSnapshot(
                jobId,
                queued.command(),
                state,
                queued.acceptedAt(),
                running.startedAt(),
                clock.instant(),
                result,
                null));
        }
        catch (final RuntimeException ex)
        {
            LOGGER.error(
                "Replay failed: jobId={}, checkpointKey={}, recordingId={}",
                jobId,
                queued.command().checkpointKey(),
                queued.command().recordingId(),
                ex);
            final ReplayJobSnapshot running = jobs.get(jobId);
            jobs.put(jobId, new ReplayJobSnapshot(
                jobId,
                queued.command(),
                ReplayJobState.FAILED,
                queued.acceptedAt(),
                running.startedAt(),
                clock.instant(),
                null,
                safeMessage(ex)));
        }
        finally
        {
            activeCheckpointKeys.remove(queued.command().checkpointKey());
        }
    }

    private static String safeMessage(final RuntimeException ex)
    {
        return ex.getMessage() == null || ex.getMessage().isBlank() ?
            ex.getClass().getSimpleName() : ex.getMessage();
    }
}
