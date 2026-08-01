package io.github.mikuwwl.matchingreplay.aeron;

import java.nio.file.Files;
import java.nio.file.Path;

public final class RunManifestStore
{
    private final Path path;

    public RunManifestStore(final Path path)
    {
        this.path = path.toAbsolutePath().normalize();
    }

    public void write(final RunManifest manifest)
    {
        AtomicPropertiesFile.write(path, manifest.toProperties(), "matching replay run manifest");
    }

    public RunManifest readRequired()
    {
        if (!Files.exists(path))
        {
            throw new IllegalStateException(
                "Run manifest is missing; refusing to choose an ambiguous recording: " + path);
        }
        return RunManifest.fromProperties(AtomicPropertiesFile.read(path));
    }

    public Path path()
    {
        return path;
    }
}
