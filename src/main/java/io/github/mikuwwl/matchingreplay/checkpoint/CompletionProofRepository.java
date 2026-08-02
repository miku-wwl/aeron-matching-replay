package io.github.mikuwwl.matchingreplay.checkpoint;

import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.failure.ReplayException;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailure;
import io.github.mikuwwl.matchingreplay.failure.ReplayFailureCode;
import org.springframework.stereotype.Repository;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

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

    public void saveIfAbsent(final CompletionProof proof)
    {
        try
        {
            AtomicPropertiesFile.writeNew(
                pathFor(proof.checkpointKey(), proof.attemptId()),
                proof.toProperties(),
                "Verified immutable Aeron replay completion proof");
        }
        catch (final RuntimeException ex)
        {
            final ReplayFailureCode code = causedByFileAlreadyExists(ex) ?
                ReplayFailureCode.COMPLETION_PROOF_ALREADY_EXISTS :
                ReplayFailureCode.COMPLETION_PROOF_WRITE_FAILED;
            throw new ReplayException(
                ReplayFailure.basic(
                    code,
                    code == ReplayFailureCode.COMPLETION_PROOF_ALREADY_EXISTS ?
                        "Completion proof already exists for attemptId=" +
                            proof.attemptId() :
                        "Failed to write completion proof for attemptId=" +
                            proof.attemptId()),
                ex);
        }
    }

    public Optional<CompletionProof> findByAttemptId(
        final String checkpointKey,
        final UUID attemptId)
    {
        final Path path = pathFor(checkpointKey, attemptId);
        return Files.exists(path) ? Optional.of(read(path)) : Optional.empty();
    }

    public Optional<CompletionProof> findByJobId(final UUID jobId)
    {
        if (jobId == null || !Files.exists(proofDirectory))
        {
            return Optional.empty();
        }
        try (Stream<Path> paths = Files.walk(proofDirectory, 2))
        {
            return paths
                .filter(CompletionProofRepository::isProofFile)
                .map(this::read)
                .filter(proof -> proof.jobId().equals(jobId))
                .findFirst();
        }
        catch (final IOException ex)
        {
            throw readFailure("Unable to scan completion proofs for jobId=" + jobId, ex);
        }
    }

    public List<CompletionProof> findByCheckpointKey(final String checkpointKey)
    {
        final Path checkpointProofDirectory = directoryFor(checkpointKey);
        if (!Files.exists(checkpointProofDirectory))
        {
            return List.of();
        }
        try (Stream<Path> paths = Files.list(checkpointProofDirectory))
        {
            return paths
                .filter(CompletionProofRepository::isProofFile)
                .map(this::read)
                .sorted(Comparator.comparing(CompletionProof::completedAt)
                    .thenComparing(CompletionProof::attemptId))
                .toList();
        }
        catch (final IOException ex)
        {
            throw readFailure(
                "Unable to list completion proofs for checkpointKey=" + checkpointKey,
                ex);
        }
    }

    public Path pathFor(final String checkpointKey, final UUID attemptId)
    {
        if (attemptId == null)
        {
            throw new IllegalArgumentException("attemptId is required");
        }
        final Path directory = directoryFor(checkpointKey);
        final Path path = directory.resolve(attemptId + ".properties").normalize();
        if (!path.startsWith(directory))
        {
            throw new IllegalArgumentException("attemptId escapes proof directory");
        }
        return path;
    }

    private Path directoryFor(final String checkpointKey)
    {
        if (checkpointKey == null || !SAFE_KEY.matcher(checkpointKey).matches())
        {
            throw new IllegalArgumentException(
                "checkpointKey must match " + SAFE_KEY.pattern());
        }
        final Path directory = proofDirectory.resolve(checkpointKey).normalize();
        if (!directory.startsWith(proofDirectory))
        {
            throw new IllegalArgumentException("checkpointKey escapes proof directory");
        }
        return directory;
    }

    private CompletionProof read(final Path path)
    {
        try
        {
            return CompletionProof.fromProperties(AtomicPropertiesFile.read(path));
        }
        catch (final RuntimeException ex)
        {
            throw readFailure("Corrupt completion proof " + path, ex);
        }
    }

    private static ReplayException readFailure(
        final String message,
        final Exception cause)
    {
        return new ReplayException(
            ReplayFailure.basic(ReplayFailureCode.COMPLETION_PROOF_CORRUPTED, message),
            cause);
    }

    private static boolean isProofFile(final Path path)
    {
        return Files.isRegularFile(path) &&
            path.getFileName().toString().endsWith(".properties");
    }

    private static boolean causedByFileAlreadyExists(final Throwable throwable)
    {
        for (Throwable current = throwable; current != null; current = current.getCause())
        {
            if (current instanceof FileAlreadyExistsException)
            {
                return true;
            }
        }
        return false;
    }
}
