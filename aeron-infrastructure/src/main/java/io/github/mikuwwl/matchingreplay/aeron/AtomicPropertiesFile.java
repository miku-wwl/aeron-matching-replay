package io.github.mikuwwl.matchingreplay.aeron;

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
        try
        {
            Files.createDirectories(target.toAbsolutePath().normalize().getParent());
            final ByteArrayOutputStream output = new ByteArrayOutputStream();
            properties.store(output, comment);
            final ByteBuffer bytes = ByteBuffer.wrap(output.toByteArray());
            final Path temp = target.resolveSibling(target.getFileName() + ".tmp");
            try (FileChannel channel = FileChannel.open(
                temp,
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
                    temp,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            }
            catch (final AtomicMoveNotSupportedException ex)
            {
                System.err.println("WARN atomic move unsupported for " + target + "; using replacement move");
                Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        catch (final IOException ex)
        {
            throw new IllegalStateException("Failed to atomically write " + target, ex);
        }
    }

    static Properties read(final Path target)
    {
        final Properties properties = new Properties();
        try (var input = Files.newInputStream(target))
        {
            properties.load(input);
            return properties;
        }
        catch (final IOException ex)
        {
            throw new IllegalStateException("Failed to read " + target, ex);
        }
    }

    static String require(final Properties properties, final String name)
    {
        final String value = properties.getProperty(name);
        if (value == null || value.isBlank())
        {
            throw new IllegalStateException("Missing required property: " + name);
        }
        return value;
    }
}
