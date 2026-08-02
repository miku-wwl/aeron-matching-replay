package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

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

    @Test
    void completionProofIsStoredSeparatelyFromProgressCheckpoint()
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setCheckpointDirectory(tempDirectory);
        final CheckpointRepository checkpoints = new CheckpointRepository(properties);
        final CompletionProofRepository proofs = new CompletionProofRepository(properties);
        final Checkpoint checkpoint = Checkpoint.initial("orders", 42, 128);
        final CompletionProof proof = new CompletionProof(
            "orders",
            42,
            128,
            1_024,
            10,
            99,
            CompletionVerificationStatus.VERIFIED,
            Instant.parse("2026-08-02T00:00:00Z"));

        checkpoints.save(checkpoint);
        proofs.save(proof);

        assertEquals(checkpoint, checkpoints.find("orders").orElseThrow());
        assertEquals(proof, proofs.find("orders").orElseThrow());
        assertTrue(!checkpoints.pathFor("orders").equals(proofs.pathFor("orders")));
    }
}
