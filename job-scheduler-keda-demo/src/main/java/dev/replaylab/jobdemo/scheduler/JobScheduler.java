package dev.replaylab.jobdemo.scheduler;

import dev.replaylab.jobdemo.metrics.DemoMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "scheduler")
public class JobScheduler {

    private static final Logger log = LoggerFactory.getLogger(JobScheduler.class);

    private final SchedulerRepository repository;
    private final DemoMetrics metrics;
    private final int batchSize;

    public JobScheduler(SchedulerRepository repository,
                        DemoMetrics metrics,
                        @Value("${app.scheduler.batch-size:50}") int batchSize) {
        this.repository = repository;
        this.metrics = metrics;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${app.scheduler.poll-delay:1s}")
    public void dispatchDueJobs() {
        int dispatched = repository.dispatchDue(batchSize);
        if (dispatched > 0) {
            metrics.schedulerDispatched().increment(dispatched);
            log.info("Dispatched {} due jobs into the transactional outbox", dispatched);
        }
    }
}
