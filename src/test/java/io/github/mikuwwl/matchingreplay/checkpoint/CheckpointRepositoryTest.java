package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CheckpointRepositoryTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void atomicallyRoundTripsCheckpointAndRejectsPathTraversal()
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setCheckpointDirectory(tempDirectory);
        final CheckpointRepository repository = new CheckpointRepository(properties);
        final Checkpoint checkpoint = Checkpoint.initial("orders", 42, 128);

        repository.save(checkpoint);

        assertEquals(checkpoint, repository.find("orders").orElseThrow());
        assertTrue(repository.pathFor("orders").startsWith(tempDirectory));
        assertThrows(IllegalArgumentException.class, () -> repository.pathFor("../escape"));
    }
}
