package dev.replaylab.jobdemo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
@ConditionalOnProperty(name = "app.role", havingValue = "worker")
public class JobWorker {

    private static final Logger log = LoggerFactory.getLogger(JobWorker.class);

    private final ObjectMapper objectMapper;
    private final String workerId;

    public JobWorker(ObjectMapper objectMapper,
                     @Value("${app.worker.id}") String workerId) {
        this.objectMapper = objectMapper;
        this.workerId = workerId;
    }

    @RabbitListener(queues = "${app.rabbit.ready-queue}")
    public void consume(String message) throws IOException, InterruptedException {
        JobEvent event = objectMapper.readValue(message, JobEvent.class);
        log.info("Started eventId={} jobKey={} workerId={} durationMs={}",
                event.eventId(), event.jobKey(), workerId, event.durationMs());
        Thread.sleep(event.durationMs());
        log.info("Completed eventId={} jobKey={} workerId={}",
                event.eventId(), event.jobKey(), workerId);
    }
}
