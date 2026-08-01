package io.github.mikuwwl.matchingreplay.aeron;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointStoreTest
{
    @TempDir
    Path tempDir;

    @Test
    void missingCheckpointReturnsEmptyAndWritesRoundTrip() throws Exception
    {
        final Path path = tempDir.resolve("consumer.checkpoint");
        final CheckpointStore store = new CheckpointStore(path);
        assertTrue(store.read().isEmpty());

        final Checkpoint first = checkpoint(1, 64, 11);
        store.write(first);
        assertEquals(first, store.read().orElseThrow());
        assertFalse(Files.exists(path.resolveSibling(path.getFileName() + ".tmp")));

        final Checkpoint replacement = checkpoint(2, 128, 22);
        store.write(replacement);
        assertEquals(replacement, store.read().orElseThrow());
        assertEquals(2, store.writeCount());
        assertTrue(store.totalWriteLatencyNs() > 0);
    }

    @Test
    void corruptCheckpointFailsFast() throws Exception
    {
        final Path path = tempDir.resolve("bad.checkpoint");
        Files.writeString(path, "consumerName=x\nrecordingId=not-a-number\n");
        final CheckpointStore store = new CheckpointStore(path);
        assertThrows(IllegalStateException.class, store::read);
    }

    private static Checkpoint checkpoint(final long sequence, final long position, final long hash)
    {
        return new Checkpoint("consumer", 7, sequence, position, sequence, 0, 0, hash, Instant.EPOCH);
    }
}
