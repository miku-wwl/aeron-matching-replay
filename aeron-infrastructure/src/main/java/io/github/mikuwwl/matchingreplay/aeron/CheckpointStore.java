package io.github.mikuwwl.matchingreplay.aeron;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

public final class CheckpointStore
{
    private final Path path;
    private long writeCount;
    private long totalWriteLatencyNs;

    public CheckpointStore(final Path path)
    {
        this.path = path.toAbsolutePath().normalize();
    }

    public void write(final Checkpoint checkpoint)
    {
        final long started = System.nanoTime();
        AtomicPropertiesFile.write(path, checkpoint.toProperties(), "matching replay checkpoint");
        writeCount++;
        totalWriteLatencyNs += System.nanoTime() - started;
    }

    public Optional<Checkpoint> read()
    {
        if (!Files.exists(path))
        {
            return Optional.empty();
        }
        return Optional.of(Checkpoint.fromProperties(AtomicPropertiesFile.read(path)));
    }

    public Path path()
    {
        return path;
    }

    public long writeCount()
    {
        return writeCount;
    }

    public long totalWriteLatencyNs()
    {
        return totalWriteLatencyNs;
    }
}
