package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.springframework.stereotype.Repository;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.regex.Pattern;

@Repository
public class CompletionProofRepository
{
    private static final Pattern SAFE_KEY =
        Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    private final Path proofDirectory;

    public CompletionProofRepository(final ReplayProperties properties)
    {
        proofDirectory = properties.getCheckpointDirectory()
            .toAbsolutePath()
            .normalize()
            .resolve("completion-proofs");
    }

    public Optional<CompletionProof> find(final String checkpointKey)
    {
        final Path path = pathFor(checkpointKey);
        if (!Files.exists(path))
        {
            return Optional.empty();
        }
        try
        {
            return Optional.of(CompletionProof.fromProperties(
                AtomicPropertiesFile.read(path)));
        }
        catch (final RuntimeException ex)
        {
            throw new ReplayException(
                ReplayFailure.basic(
                    ReplayFailureCode.CHECKPOINT_CORRUPTED,
                    "Corrupt completion proof for checkpointKey=" + checkpointKey),
                ex);
        }
    }

    public void save(final CompletionProof proof)
    {
        try
        {
            AtomicPropertiesFile.write(
                pathFor(proof.checkpointKey()),
                proof.toProperties(),
                "Verified Aeron replay completion proof");
        }
        catch (final RuntimeException ex)
        {
            throw new ReplayException(
                ReplayFailure.basic(
                    ReplayFailureCode.COMPLETION_PROOF_WRITE_FAILED,
                    "Failed to write completion proof for checkpointKey=" +
                        proof.checkpointKey()),
                ex);
        }
    }

    public Path pathFor(final String checkpointKey)
    {
        if (checkpointKey == null || !SAFE_KEY.matcher(checkpointKey).matches())
        {
            throw new IllegalArgumentException(
                "checkpointKey must match " + SAFE_KEY.pattern());
        }
        final Path path = proofDirectory.resolve(checkpointKey + ".properties").normalize();
        if (!path.startsWith(proofDirectory))
        {
            throw new IllegalArgumentException("checkpointKey escapes proof directory");
        }
        return path;
    }
}
