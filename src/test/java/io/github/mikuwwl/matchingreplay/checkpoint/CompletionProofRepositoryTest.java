package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompletionProofRepositoryTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void successfulReplayCreatesAttemptSpecificCompletionProof()
    {
        final CompletionProofRepository repository = repository();
        final CompletionProof proof = proof(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            1_024,
            false);

        repository.saveIfAbsent(proof);

        assertEquals(
            proof,
            repository.findByAttemptId("orders", proof.attemptId()).orElseThrow());
        assertEquals(proof, repository.findByJobId(proof.jobId()).orElseThrow());
        assertTrue(Files.exists(repository.pathFor("orders", proof.attemptId())));
    }

    @Test
    void existingCompletionProofIsImmutable()
    {
        final CompletionProofRepository repository = repository();
        final UUID jobId = UUID.randomUUID();
        final UUID attemptId = UUID.randomUUID();
        final CompletionProof original = proof(jobId, attemptId, 0, 1_024, false);
        repository.saveIfAbsent(original);
        final CompletionProof replacement = new CompletionProof(
            jobId,
            attemptId,
            "replacement",
            "orders",
            42,
            1_024,
            2_048,
            20,
            199,
            true,
            CompletionVerificationStatus.VERIFIED,
            Instant.parse("2026-08-02T01:00:00Z"));

        final ReplayException exception = assertThrows(
            ReplayException.class,
            () -> repository.saveIfAbsent(replacement));

        assertEquals(
            ReplayFailureCode.COMPLETION_PROOF_ALREADY_EXISTS,
            exception.failure().code());
        assertEquals(
            original,
            repository.findByAttemptId("orders", attemptId).orElseThrow());
    }

    @Test
    void secondSuccessfulReplayDoesNotOverwritePreviousProof()
    {
        final CompletionProofRepository repository = repository();
        final CompletionProof first = proof(
            UUID.randomUUID(),
            UUID.randomUUID(),
            0,
            1_024,
            false);
        final CompletionProof noOp = proof(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1_024,
            1_024,
            true);

        repository.saveIfAbsent(first);
        repository.saveIfAbsent(noOp);

        final List<CompletionProof> proofs =
            repository.findByCheckpointKey("orders");
        assertEquals(2, proofs.size());
        assertEquals(0, proofs.get(0).replayStartPosition());
        assertEquals(1_024, proofs.get(1).replayStartPosition());
        assertEquals(1_024, proofs.get(0).replayStopPosition());
        assertEquals(1_024, proofs.get(1).replayStopPosition());
    }

    @Test
    void rejectsCheckpointPathTraversal()
    {
        assertThrows(
            IllegalArgumentException.class,
            () -> repository().pathFor("../escape", UUID.randomUUID()));
    }

    private CompletionProofRepository repository()
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setCheckpointDirectory(tempDirectory);
        return new CompletionProofRepository(properties);
    }

    private static CompletionProof proof(
        final UUID jobId,
        final UUID attemptId,
        final long replayStart,
        final long replayStop,
        final boolean resumed)
    {
        return new CompletionProof(
            jobId,
            attemptId,
            "release-test",
            "orders",
            42,
            replayStart,
            replayStop,
            10,
            99,
            resumed,
            CompletionVerificationStatus.VERIFIED,
            Instant.parse("2026-08-02T00:00:00Z").plusSeconds(replayStart));
    }
}
