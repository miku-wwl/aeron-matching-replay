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
            catch (final AtomicMoveNotSupportedException ignored)
            {
                Files.move(temporary, normalized, StandardCopyOption.REPLACE_EXISTING);
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
