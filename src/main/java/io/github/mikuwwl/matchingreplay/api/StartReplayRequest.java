package io.github.mikuwwl.matchingreplay.api;

import io.github.mikuwwl.matchingreplay.aeron.ReplayCommand;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record StartReplayRequest(
    @NotNull @PositiveOrZero Long recordingId,
    @Pattern(regexp = "[A-Za-z0-9][A-Za-z0-9._-]{0,127}") String checkpointKey,
    @PositiveOrZero Long stopPosition,
    @NotNull @PositiveOrZero Long expectedLastEventSequence,
    @NotNull @Pattern(regexp = "[0-9]{1,20}") String expectedStateHash,
    @Size(max = 128) String correlationId)
{
    public ReplayCommand toCommand()
    {
        final String effectiveCheckpointKey =
            checkpointKey == null || checkpointKey.isBlank() ? "default" : checkpointKey;
        final long parsedStateHash = Long.parseUnsignedLong(expectedStateHash);
        return new ReplayCommand(
            recordingId.longValue(),
            effectiveCheckpointKey,
            stopPosition,
            expectedLastEventSequence.longValue(),
            parsedStateHash,
            correlationId);
    }
}
