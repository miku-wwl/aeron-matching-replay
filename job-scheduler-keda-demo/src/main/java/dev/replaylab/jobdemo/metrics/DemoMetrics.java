package dev.replaylab.jobdemo.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

@Component
public class DemoMetrics {

    private final Counter schedulerDispatched;
    private final Counter outboxPublished;
    private final Counter leaseAcquired;
    private final Counter leaseConflicts;
    private final Counter leaseLost;
    private final Counter checkpointUpdates;
    private final Counter duplicateMessages;
    private final Counter redeliveredMessages;
    private final Timer jobDuration;
    private final AtomicInteger workerActive = new AtomicInteger();

    public DemoMetrics(MeterRegistry registry) {
        schedulerDispatched = registry.counter("demo.scheduler.dispatched");
        outboxPublished = registry.counter("demo.outbox.published");
        leaseAcquired = registry.counter("demo.lease.acquired");
        leaseConflicts = registry.counter("demo.lease.conflicts");
        leaseLost = registry.counter("demo.lease.lost");
        checkpointUpdates = registry.counter("demo.checkpoint.updates");
        duplicateMessages = registry.counter("demo.queue.duplicates");
        redeliveredMessages = registry.counter("demo.queue.redelivered");
        jobDuration = registry.timer("demo.job.duration");
        registry.gauge("demo.worker.active", workerActive);
    }

    public Counter schedulerDispatched() { return schedulerDispatched; }
    public Counter outboxPublished() { return outboxPublished; }
    public Counter leaseAcquired() { return leaseAcquired; }
    public Counter leaseConflicts() { return leaseConflicts; }
    public Counter leaseLost() { return leaseLost; }
    public Counter checkpointUpdates() { return checkpointUpdates; }
    public Counter duplicateMessages() { return duplicateMessages; }
    public Counter redeliveredMessages() { return redeliveredMessages; }
    public Timer jobDuration() { return jobDuration; }
    public AtomicInteger workerActive() { return workerActive; }
}
