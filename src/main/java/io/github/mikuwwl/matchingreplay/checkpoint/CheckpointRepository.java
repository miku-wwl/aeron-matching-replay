package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import io.github.mikuwwl.matchingreplay.observability.ReplayMetrics;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
public class CheckpointRepository
{
    private static final Pattern SAFE_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path checkpointDirectory;
    private final ReplayMetrics metrics;

    @Autowired
    public CheckpointRepository(
        final ReplayProperties properties,
        final ReplayMetrics metrics)
    {
        checkpointDirectory = properties.getCheckpointDirectory().toAbsolutePath().normalize();
        this.metrics = metrics;
    }

    public CheckpointRepository(final ReplayProperties properties)
    {
        this(properties, ReplayMetrics.noop());
    }

    public Optional<Checkpoint> find(final String checkpointKey)
    {
        final Path path = pathFor(checkpointKey);
        if (!Files.exists(path))
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(Checkpoint.fromProperties(AtomicPropertiesFile.read(path)));
        }
        catch (final RuntimeException ex)
        {
            throw new ReplayException(
                ReplayFailure.basic(
                    ReplayFailureCode.CHECKPOINT_CORRUPTED,
                    "Corrupt progress checkpoint for checkpointKey=" + checkpointKey),
                ex);
        }
    }

    public void save(final Checkpoint checkpoint)
    {
        try
        {
            AtomicPropertiesFile.write(
                pathFor(checkpoint.checkpointKey()),
                checkpoint.toProperties(),
                "Aeron replay progress checkpoint");
            metrics.checkpointWritten();
        }
        catch (final RuntimeException ex)
        {
            metrics.checkpointWriteFailed();
            throw new ReplayException(
                ReplayFailure.basic(
                    ReplayFailureCode.CHECKPOINT_WRITE_FAILED,
                    "Failed to write progress checkpoint for checkpointKey=" +
                        checkpoint.checkpointKey()),
                ex);
        }
    }

    public Path pathFor(final String checkpointKey)
    {
        if (checkpointKey == null || !SAFE_KEY.matcher(checkpointKey).matches())
        {
            throw new IllegalArgumentException(
                "checkpointKey must match " + SAFE_KEY.pattern());
        }
        final Path path = checkpointDirectory.resolve(checkpointKey + ".properties").normalize();
        if (!path.startsWith(checkpointDirectory))
        {
            throw new IllegalArgumentException("checkpointKey escapes checkpoint directory");
        }
        return path;
    }
}
