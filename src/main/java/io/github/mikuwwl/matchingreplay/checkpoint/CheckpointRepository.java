package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
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

    public CheckpointRepository(final ReplayProperties properties)
    {
        checkpointDirectory = properties.getCheckpointDirectory().toAbsolutePath().normalize();
    }

    public Optional<Checkpoint> find(final String checkpointKey)
    {
        final Path path = pathFor(checkpointKey);
        if (!Files.exists(path))
        {
            return Optional.empty();
        }
        return Optional.of(Checkpoint.fromProperties(AtomicPropertiesFile.read(path)));
    }

    public void save(final Checkpoint checkpoint)
    {
        AtomicPropertiesFile.write(
            pathFor(checkpoint.checkpointKey()),
            checkpoint.toProperties(),
            "Aeron replay checkpoint");
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
