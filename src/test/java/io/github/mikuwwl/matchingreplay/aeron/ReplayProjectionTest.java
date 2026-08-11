package io.github.mikuwwl.matchingreplay.aeron;

import io.aeron.logbuffer.Header;
import io.github.mikuwwl.matchingreplay.checkpoint.Checkpoint;
import io.github.mikuwwl.matchingreplay.checkpoint.CheckpointRepository;
import io.github.mikuwwl.matchingreplay.codec.MatchingEventSbeEncoder;
import io.github.mikuwwl.matchingreplay.config.ReplayProperties;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.MatchingEvent;
import io.github.mikuwwl.matchingreplay.domain.Side;
import io.github.mikuwwl.matchingreplay.projection.ProjectionState;
import io.github.mikuwwl.matchingreplay.projection.ReplayProjection;
import org.agrona.concurrent.UnsafeBuffer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReplayProjectionTest
{
    @TempDir
    Path tempDirectory;

    @Test
    void projectionReceivesOnlyNewlyAppliedEvents()
    {
        final ReplayProperties properties = new ReplayProperties();
        properties.setAeronDirectory(tempDirectory.resolve("aeron"));
        properties.setCheckpointDirectory(tempDirectory.resolve("checkpoints"));
        final CheckpointRepository checkpoints = new CheckpointRepository(properties);
        final ProjectionState state = ProjectionState.from(
            Checkpoint.initial("orders", 7, 64));
        final List<Long> appliedSequences = new ArrayList<>();
        final ReplayProjection projection = event ->
            appliedSequences.add(event.eventSequence());
        final ReplayFragmentHandler handler = new ReplayFragmentHandler(
            state,
            checkpoints,
            "orders",
            7,
            100,
            64,
            192,
            ReplayProgressListener.none(),
            List.of(projection));

        final MatchingEvent event = event(1);
        final UnsafeBuffer buffer = new UnsafeBuffer(
            new byte[MatchingEventSbeEncoder.MAX_ENCODED_LENGTH]);
        final int encodedLength = new MatchingEventSbeEncoder().encode(event, buffer, 0);
        final Header firstHeader = mock(Header.class);
        when(firstHeader.position()).thenReturn(128L);
        final Header duplicateHeader = mock(Header.class);
        when(duplicateHeader.position()).thenReturn(192L);

        handler.onFragment(buffer, 0, encodedLength, firstHeader);
        handler.onFragment(buffer, 0, encodedLength, duplicateHeader);
        handler.throwIfFailed();

        assertEquals(List.of(1L), appliedSequences);
        assertEquals(192L, state.lastAppliedAeronPosition());
        assertEquals(1, state.duplicatesTotal());
    }

    private static MatchingEvent event(final long sequence)
    {
        return new MatchingEvent(
            (short)2,
            EventType.ORDER_ACCEPTED,
            sequence,
            1_000_000 + sequence,
            10_000 + sequence,
            0,
            0,
            1,
            Side.BUY,
            100_000 + sequence,
            10 + sequence,
            10 + sequence);
    }
}
