package dev.replaylab.jobdemo.worker;

import com.rabbitmq.client.Channel;
import dev.replaylab.jobdemo.domain.JobClaim;
import dev.replaylab.jobdemo.domain.JobMessage;
import dev.replaylab.jobdemo.domain.WorkChecksum;
import dev.replaylab.jobdemo.metrics.DemoMetrics;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final WorkerCoordinator coordinator;
    private final DemoMetrics metrics;
    private final String workerId;
    private final long heartbeatNanos;
    private final long busyRequeueMillis;
    private final AtomicBoolean stopping = new AtomicBoolean();

    public JobWorker(WorkerCoordinator coordinator,
                     DemoMetrics metrics,
                     @Value("${app.worker.id}") String workerId,
                     @Value("${app.worker.lease-duration:30s}") Duration leaseDuration,
                     @Value("${app.worker.heartbeat-interval:10s}") Duration heartbeatInterval,
                     @Value("${app.worker.busy-requeue-delay:1s}") Duration busyRequeueDelay) {
        if (heartbeatInterval.isZero() || heartbeatInterval.isNegative()
                || heartbeatInterval.compareTo(leaseDuration) >= 0) {
            throw new IllegalArgumentException("Heartbeat interval must be positive and shorter than the lease");
        }
        if (busyRequeueDelay.isZero() || busyRequeueDelay.isNegative()) {
            throw new IllegalArgumentException("Busy requeue delay must be positive");
        }
        this.coordinator = coordinator;
        this.metrics = metrics;
        this.workerId = workerId;
        this.heartbeatNanos = heartbeatInterval.toNanos();
        this.busyRequeueMillis = busyRequeueDelay.toMillis();
    }

    @RabbitListener(queues = "${app.rabbit.ready-queue}")
    public void consume(JobMessage event, Message rawMessage, Channel channel) throws IOException {
        long deliveryTag = rawMessage.getMessageProperties().getDeliveryTag();
        boolean redelivered = rawMessage.getMessageProperties().isRedelivered();
        if (redelivered) {
            metrics.redeliveredMessages().increment();
        }

        JobClaim claim = coordinator.claim(event.jobId(), workerId);
        switch (claim.outcome()) {
            case NOT_FOUND, DUPLICATE_OR_TERMINAL -> {
                metrics.duplicateMessages().increment();
                log.info("ACK duplicate/stale message outboxId={} jobId={} outcome={}",
                        event.outboxId(), event.jobId(), claim.outcome());
                channel.basicAck(deliveryTag, false);
            }
            case BUSY -> handleBusy(event, channel, deliveryTag, redelivered);
            case ACQUIRED -> execute(claim, channel, deliveryTag);
        }
    }

    private void handleBusy(JobMessage event, Channel channel, long deliveryTag, boolean redelivered)
            throws IOException {
        if (!redelivered) {
            metrics.duplicateMessages().increment();
            log.info("ACK duplicate delivery while another lease is active outboxId={} jobId={}",
                    event.outboxId(), event.jobId());
            channel.basicAck(deliveryTag, false);
            return;
        }
        sleepQuietly(busyRequeueMillis);
        log.info("NACK redelivery while waiting for expired lease outboxId={} jobId={}",
                event.outboxId(), event.jobId());
        channel.basicNack(deliveryTag, false, true);
    }

    private void execute(JobClaim claim, Channel channel, long deliveryTag) throws IOException {
        Timer.Sample timer = Timer.start();
        metrics.workerActive().incrementAndGet();
        int completedUnit = claim.lastCompletedUnit();
        long checksum = claim.checksum();
        long nextHeartbeat = System.nanoTime() + heartbeatNanos;
        int failAtUnit = Math.min(claim.totalUnits(),
                claim.lastCompletedUnit() + claim.checkpointEvery());

        log.info("Started jobId={} jobKey={} attemptId={} attempt={} workerId={} token={} resumeUnit={}",
                claim.jobId(), claim.jobKey(), claim.attemptId(), claim.attemptCount(),
                claim.workerId(), claim.fencingToken(), completedUnit + 1);
        try {
            for (int unit = completedUnit + 1; unit <= claim.totalUnits(); unit++) {
                if (checkpointForShutdown(claim, completedUnit, checksum, channel, deliveryTag)) {
                    return;
                }

                if (claim.unitDelayMs() > 0) {
                    nextHeartbeat = waitWithHeartbeats(claim, claim.unitDelayMs(), nextHeartbeat);
                }
                if (checkpointForShutdown(claim, completedUnit, checksum, channel, deliveryTag)) {
                    return;
                }
                checksum = WorkChecksum.mix(checksum, unit);
                completedUnit = unit;

                if (completedUnit % claim.checkpointEvery() == 0) {
                    coordinator.saveCheckpoint(claim, completedUnit, checksum);
                    log.info("Checkpoint jobId={} token={} lastCompletedUnit={} checksum={}",
                            claim.jobId(), claim.fencingToken(), completedUnit, checksum);
                }
                if (System.nanoTime() >= nextHeartbeat) {
                    coordinator.heartbeat(claim);
                    nextHeartbeat = System.nanoTime() + heartbeatNanos;
                }
                if (claim.attemptCount() <= claim.failUntilAttempt() && completedUnit >= failAtUnit) {
                    throw new SimulatedFailureException("Configured failure for attempt " + claim.attemptCount());
                }
            }

            coordinator.succeed(claim, completedUnit, checksum);
            channel.basicAck(deliveryTag, false);
            log.info("Succeeded jobId={} jobKey={} attemptId={} token={} finalUnit={} checksum={}",
                    claim.jobId(), claim.jobKey(), claim.attemptId(), claim.fencingToken(),
                    completedUnit, checksum);
        } catch (SimulatedFailureException exception) {
            FailureResult result = coordinator.fail(
                    claim, completedUnit, checksum, "SIMULATED_FAILURE");
            acknowledgeFailure(channel, deliveryTag, result);
            log.warn("Simulated failure jobId={} attempt={} terminal={} retryDelaySeconds={}",
                    claim.jobId(), claim.attemptCount(), result.terminal(), result.retryDelaySeconds());
        } catch (LeaseLostException exception) {
            log.warn("Stale worker stopped jobId={} token={}: {}",
                    claim.jobId(), claim.fencingToken(), exception.getMessage());
            channel.basicNack(deliveryTag, false, true);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            handleUnexpectedFailure(claim, completedUnit, checksum, "INTERRUPTED", channel, deliveryTag);
        } catch (RuntimeException exception) {
            log.error("Unexpected worker failure jobId={}", claim.jobId(), exception);
            handleUnexpectedFailure(claim, completedUnit, checksum,
                    "UNEXPECTED_" + exception.getClass().getSimpleName(), channel, deliveryTag);
        } finally {
            metrics.workerActive().decrementAndGet();
            timer.stop(metrics.jobDuration());
        }
    }

    private long waitWithHeartbeats(JobClaim claim, long delayMillis, long nextHeartbeat)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(delayMillis);
        long maximumSleep = TimeUnit.MILLISECONDS.toNanos(250);
        while (!stopping.get()) {
            long now = System.nanoTime();
            if (now >= deadline) {
                return nextHeartbeat;
            }
            if (now >= nextHeartbeat) {
                coordinator.heartbeat(claim);
                nextHeartbeat = System.nanoTime() + heartbeatNanos;
                continue;
            }
            long untilDeadline = deadline - now;
            long untilHeartbeat = nextHeartbeat - now;
            TimeUnit.NANOSECONDS.sleep(Math.min(maximumSleep,
                    Math.min(untilDeadline, untilHeartbeat)));
        }
        return nextHeartbeat;
    }

    private boolean checkpointForShutdown(JobClaim claim, int unit, long checksum,
                                          Channel channel, long deliveryTag) throws IOException {
        if (!stopping.get()) {
            return false;
        }
        FailureResult result = coordinator.fail(claim, unit, checksum, "GRACEFUL_SHUTDOWN");
        acknowledgeFailure(channel, deliveryTag, result);
        log.info("Graceful shutdown checkpointed jobId={} at unit={}", claim.jobId(), unit);
        return true;
    }

    private void handleUnexpectedFailure(JobClaim claim, int unit, long checksum, String failureCode,
                                         Channel channel, long deliveryTag) throws IOException {
        try {
            FailureResult result = coordinator.fail(claim, unit, checksum, failureCode);
            acknowledgeFailure(channel, deliveryTag, result);
        } catch (LeaseLostException leaseLost) {
            channel.basicNack(deliveryTag, false, true);
        }
    }

    private static void acknowledgeFailure(Channel channel, long deliveryTag, FailureResult result)
            throws IOException {
        if (result.terminal()) {
            channel.basicReject(deliveryTag, false);
        } else {
            channel.basicAck(deliveryTag, false);
        }
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @PreDestroy
    void stop() {
        stopping.set(true);
    }

    @EventListener(ContextClosedEvent.class)
    void onContextClosed() {
        stopping.set(true);
    }

    private static final class SimulatedFailureException extends RuntimeException {
        private SimulatedFailureException(String message) {
            super(message);
        }
    }
}
