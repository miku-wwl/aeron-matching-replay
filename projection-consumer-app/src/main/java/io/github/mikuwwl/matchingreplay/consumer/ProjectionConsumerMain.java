package io.github.mikuwwl.matchingreplay.consumer;

import io.aeron.Aeron;
import io.aeron.FragmentAssembler;
import io.aeron.Subscription;
import io.github.mikuwwl.matchingreplay.aeron.AeronChannels;
import io.github.mikuwwl.matchingreplay.aeron.AeronClientFactory;
import io.github.mikuwwl.matchingreplay.aeron.Arguments;
import io.github.mikuwwl.matchingreplay.aeron.Checkpoint;
import io.github.mikuwwl.matchingreplay.aeron.CheckpointStore;
import io.github.mikuwwl.matchingreplay.aeron.CheckpointingFragmentHandler;
import io.github.mikuwwl.matchingreplay.aeron.ProjectionState;
import io.github.mikuwwl.matchingreplay.aeron.RunManifestStore;
import io.github.mikuwwl.matchingreplay.aeron.RuntimePaths;
import org.agrona.concurrent.BackoffIdleStrategy;
import org.agrona.concurrent.IdleStrategy;

import java.time.Duration;

public final class ProjectionConsumerMain
{
    private ProjectionConsumerMain()
    {
    }

    public static void main(final String[] args)
    {
        final Arguments arguments = Arguments.parse(args);
        final String mode = arguments.stringValue("mode", "live");
        if (!"live".equals(mode))
        {
            throw new IllegalArgumentException("ProjectionConsumerMain supports only --mode=live");
        }
        final long crashAfterSequence = arguments.longValue("crashAfterSequence", 4_000);
        final int checkpointEvery = arguments.intValue("checkpointEvery", 1);
        final String consumerName = arguments.stringValue("consumerName", "asset-projection");
        final int timeoutSeconds = arguments.intValue("timeoutSeconds", 180);

        final RuntimePaths paths = RuntimePaths.resolve().createDirectories();
        final CheckpointStore checkpointStore = new CheckpointStore(paths.checkpoint(consumerName));
        final ProjectionState state = checkpointStore.read()
            .map(ProjectionState::from)
            .orElseGet(ProjectionState::new);
        final RunManifestStore manifestStore = new RunManifestStore(paths.currentManifest());

        try (Aeron aeron = AeronClientFactory.connectAeron(paths, "projection-consumer");
            Subscription subscription = aeron.addSubscription(
                AeronChannels.LIVE_CHANNEL,
                AeronChannels.LIVE_STREAM_ID))
        {
            final CheckpointingFragmentHandler handler = new CheckpointingFragmentHandler(
                state,
                checkpointStore,
                consumerName,
                () -> manifestStore.readRequired().recordingId(),
                checkpointEvery,
                crashAfterSequence,
                ProjectionConsumerMain::hardCrash);
            final FragmentAssembler assembler = new FragmentAssembler(handler);
            final IdleStrategy idle = new BackoffIdleStrategy();
            final long deadline = System.nanoTime() + Duration.ofSeconds(timeoutSeconds).toNanos();

            System.out.println("CONSUMER_READY");
            System.out.println("consumer=" + consumerName);
            System.out.println("streamId=" + AeronChannels.LIVE_STREAM_ID);
            System.out.flush();

            while (System.nanoTime() < deadline)
            {
                final int fragments = subscription.poll(assembler, 10);
                idle.idle(fragments);
            }
            throw new IllegalStateException(
                "Live consumer timed out before simulated crash at sequence " + crashAfterSequence);
        }
    }

    private static void hardCrash(final Checkpoint checkpoint)
    {
        System.out.println("SIMULATED_CRASH");
        System.out.println("consumer=" + checkpoint.consumerName());
        System.out.println("recordingId=" + checkpoint.recordingId());
        System.out.println("lastSequence=" + checkpoint.lastAppliedEventSequence());
        System.out.println("checkpointPosition=" + checkpoint.lastAppliedAeronPosition());
        System.out.println("stateHash=" + Long.toUnsignedString(checkpoint.stateHash()));
        System.out.println("exitCode=77");
        System.out.flush();
        System.err.flush();
        Runtime.getRuntime().halt(77);
    }
}
