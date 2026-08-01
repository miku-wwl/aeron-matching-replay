package io.github.mikuwwl.matchingreplay.engine;

import io.github.mikuwwl.matchingreplay.aeron.AeronMatchingEventPublisher;
import io.github.mikuwwl.matchingreplay.aeron.ArchiveRecordingSession;
import io.github.mikuwwl.matchingreplay.aeron.Arguments;
import io.github.mikuwwl.matchingreplay.aeron.ProjectionState;
import io.github.mikuwwl.matchingreplay.aeron.RunManifest;
import io.github.mikuwwl.matchingreplay.aeron.RunManifestStore;
import io.github.mikuwwl.matchingreplay.aeron.RuntimePaths;
import io.github.mikuwwl.matchingreplay.domain.EventType;
import io.github.mikuwwl.matchingreplay.domain.LimitOrderCommand;
import io.github.mikuwwl.matchingreplay.orderbook.MatchingEngine;
import io.github.mikuwwl.matchingreplay.orderbook.OrderBook;
import io.github.mikuwwl.matchingreplay.orderbook.SyntheticOrderFeed;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

public final class MatchingEngineMain
{
    private MatchingEngineMain()
    {
    }

    public static void main(final String[] args)
    {
        final Arguments arguments = Arguments.parse(args);
        final int orderCount = arguments.intValue("orderCount", SyntheticOrderFeed.DEFAULT_ORDER_COUNT);
        final long seed = arguments.longValue("seed", SyntheticOrderFeed.DEFAULT_SEED);
        final int symbolId = arguments.intValue("symbolId", SyntheticOrderFeed.DEFAULT_SYMBOL_ID);
        final long publishDelayMicros = arguments.longValue("publishDelayMicros", 0);
        if (publishDelayMicros < 0)
        {
            throw new IllegalArgumentException("publishDelayMicros must not be negative");
        }

        final RuntimePaths paths = RuntimePaths.resolve().createDirectories();
        final Duration timeout = Duration.ofSeconds(60);
        final String runId = Instant.now().toString();
        final RunManifestStore manifestStore = new RunManifestStore(paths.currentManifest());

        try (ArchiveRecordingSession session = ArchiveRecordingSession.open(paths, timeout))
        {
            final RunManifest initial = RunManifest.initial(
                runId,
                session.recordingId(),
                session.publication().sessionId(),
                seed,
                orderCount);
            manifestStore.write(initial);

            final AeronMatchingEventPublisher aeronPublisher =
                new AeronMatchingEventPublisher(session.publication(), timeout);
            final ProjectionState referenceProjection = new ProjectionState();
            final AtomicLong lastPublishedPosition = new AtomicLong();
            final AtomicLong tradesCreated = new AtomicLong();
            final OrderBook orderBook = new OrderBook(symbolId);
            final MatchingEngine engine = new MatchingEngine(orderBook, event ->
            {
                final long position = aeronPublisher.publish(event);
                referenceProjection.apply(event, position);
                lastPublishedPosition.set(position);
                if (event.eventType() == EventType.TRADE_EXECUTED)
                {
                    tradesCreated.incrementAndGet();
                }
                if (publishDelayMicros > 0)
                {
                    LockSupport.parkNanos(publishDelayMicros * 1_000);
                }
                return position;
            });

            for (final LimitOrderCommand command : SyntheticOrderFeed.generate(orderCount, seed, symbolId))
            {
                engine.submit(command);
            }

            final long finalPosition = lastPublishedPosition.get();
            final long recordedPosition = session.awaitRecorded(finalPosition, timeout);
            final RunManifest completed = new RunManifest(
                runId,
                session.recordingId(),
                session.publication().sessionId(),
                initial.channel(),
                initial.liveStreamId(),
                1,
                referenceProjection.lastAppliedEventSequence(),
                aeronPublisher.eventsPublished(),
                finalPosition,
                recordedPosition,
                referenceProjection.stateHash(),
                orderBook.stateHash(),
                seed,
                orderCount,
                Instant.now());
            manifestStore.write(completed);
            session.stopRecording();

            System.out.println("ENGINE_FINISHED");
            System.out.println("runId=" + runId);
            System.out.println("ordersSubmitted=" + engine.ordersSubmitted());
            System.out.println("eventsPublished=" + aeronPublisher.eventsPublished());
            System.out.println("tradesCreated=" + tradesCreated.get());
            System.out.println("firstSequence=1");
            System.out.println("lastSequence=" + referenceProjection.lastAppliedEventSequence());
            System.out.println("publicationPosition=" + finalPosition);
            System.out.println("recordedPosition=" + recordedPosition);
            System.out.println("recordingId=" + session.recordingId());
            System.out.println("orderBookHash=" + Long.toUnsignedString(orderBook.stateHash()));
            System.out.println("expectedProjectionHash=" +
                Long.toUnsignedString(referenceProjection.stateHash()));
            System.out.println("backPressureCount=" + aeronPublisher.backPressureCount());
        }
    }
}
