package io.github.mikuwwl.matchingreplay.checkpoint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Properties;
import java.util.UUID;

final class AtomicPropertiesFile
{
    private AtomicPropertiesFile()
    {
    }

    static void write(final Path target, final Properties properties, final String comment)
    {
        final Path normalized = target.toAbsolutePath().normalize();
        try
        {
            Files.createDirectories(normalized.getParent());
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, comment);
            final ByteBuffer bytes = ByteBuffer.wrap(output.toByteArray());
            final Path temporary = normalized.resolveSibling(normalized.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE))
            {
                while (bytes.hasRemaining())
                {
                    channel.write(bytes);
                }
                channel.force(true);
            }

            try
            {
                Files.move(
                    temporary,
                    normalized,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch (final AtomicMoveNotSupportedException ex)
            {
                throw new IllegalStateException(
                    "Atomic move is required for checkpoint replacement: " + normalized,
                    ex);
            }
        }
        catch (final IOException ex)
        {
            throw new IllegalStateException("Failed to atomically write checkpoint " + normalized, ex);
        }
    }

    static Properties read(final Path path)
    {
        final Properties properties = new Properties();
        try (var input = Files.newInputStream(path))
        {
            properties.load(input);
            return properties;
        }
        catch (final IOException ex)
        {
            throw new IllegalStateException("Failed to read checkpoint " + path, ex);
        }
    }

    static void writeNew(
        final Path target,
        final Properties properties,
        final String comment)
    {
        final Path normalized = target.toAbsolutePath().normalize();
        Path temporary = null;
        try
        {
            Files.createDirectories(normalized.getParent());
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, comment);
            final ByteBuffer bytes = ByteBuffer.wrap(output.toByteArray());
            temporary = normalized.resolveSibling(
                normalized.getFileName() + ".tmp-" + UUID.randomUUID());
            try (FileChannel channel = FileChannel.open(
                temporary,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE))
            {
                while (bytes.hasRemaining())
                {
                    channel.write(bytes);
                }
                channel.force(true);
            }

            // A hard link atomically exposes the already-forced inode and, unlike
            // ATOMIC_MOVE, is specified to fail when the immutable target exists.
            Files.createLink(normalized, temporary);
            Files.delete(temporary);
            temporary = null;
        }
        catch (final IOException | UnsupportedOperationException ex)
        {
            throw new IllegalStateException(
                "Failed to atomically create immutable file " + normalized,
                ex);
        }
        finally
        {
            if (temporary != null)
            {
                try
                {
                    Files.deleteIfExists(temporary);
                }
                catch (final IOException ignored)
                {
                    // The original creation failure remains the actionable error.
                }
            }
        }
    }

    static String require(final Properties properties, final String key)
    {
        final String value = properties.getProperty(key);
        if (value == null || value.isBlank())
        {
            throw new IllegalStateException("Checkpoint property is missing: " + key);
        }
        return value;
    }
}
