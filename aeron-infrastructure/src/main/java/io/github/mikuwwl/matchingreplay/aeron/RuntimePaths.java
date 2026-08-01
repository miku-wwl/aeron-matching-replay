package io.github.mikuwwl.matchingreplay.aeron;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public record RuntimePaths(
    Path runtimeDir,
    Path aeronDir,
    Path archiveDir,
    Path checkpointsDir,
    Path manifestsDir,
    Path logsDir,
    Path pidsDir)
{
    public static RuntimePaths resolve()
    {
        final Path runtime = Paths.get(System.getProperty(AeronChannels.RUNTIME_DIR_PROPERTY, "runtime"))
            .toAbsolutePath()
            .normalize();
        final Path aeron = propertyPath(AeronChannels.AERON_DIR_PROPERTY, runtime.resolve("aeron"));
        final Path archive = propertyPath(AeronChannels.ARCHIVE_DIR_PROPERTY, runtime.resolve("archive"));
        return new RuntimePaths(
            runtime,
            aeron,
            archive,
            runtime.resolve("checkpoints"),
            runtime.resolve("manifests"),
            runtime.resolve("logs"),
            runtime.resolve("pids"));
    }

    private static Path propertyPath(final String property, final Path defaultPath)
    {
        return Paths.get(System.getProperty(property, defaultPath.toString())).toAbsolutePath().normalize();
    }

    public RuntimePaths createDirectories()
    {
        try
        {
            Files.createDirectories(runtimeDir);
            Files.createDirectories(aeronDir);
            Files.createDirectories(archiveDir);
            Files.createDirectories(checkpointsDir);
            Files.createDirectories(manifestsDir);
            Files.createDirectories(logsDir);
            Files.createDirectories(pidsDir);
            return this;
        }
        catch (final IOException ex)
        {
            throw new IllegalStateException("Failed to create runtime directories under " + runtimeDir, ex);
        }
    }

    public Path checkpoint(final String consumerName)
    {
        if (consumerName == null || !consumerName.matches("[A-Za-z0-9._-]+"))
        {
            throw new IllegalArgumentException("Invalid consumer name: " + consumerName);
        }
        return checkpointsDir.resolve(consumerName + ".checkpoint");
    }

    public Path currentManifest()
    {
        return manifestsDir.resolve("current-run.properties");
    }
}
