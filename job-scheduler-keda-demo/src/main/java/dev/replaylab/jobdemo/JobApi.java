package dev.replaylab.jobdemo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/jobs")
@ConditionalOnProperty(name = "app.role", havingValue = "api")
public class JobApi {

    private final RabbitTemplate rabbit;
    private final ObjectMapper objectMapper;
    private final String queueName;

    public JobApi(RabbitTemplate rabbit,
                  ObjectMapper objectMapper,
                  @Value("${app.rabbit.ready-queue}") String queueName) {
        this.rabbit = rabbit;
        this.objectMapper = objectMapper;
        this.queueName = queueName;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public JobEvent create(@Valid @RequestBody CreateJobRequest request) {
        int durationMs = request.durationMs() == null ? 1_000 : request.durationMs();
        JobEvent event = new JobEvent(UUID.randomUUID(), request.jobKey(), durationMs, Instant.now());
        publish(event);
        return event;
    }

    @PostMapping("/burst")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public BurstResult burst(@RequestParam(defaultValue = "100") int count,
                             @RequestParam(defaultValue = "1000") int durationMs) {
        if (count < 1 || count > 500 || durationMs < 1 || durationMs > 60_000) {
            throw new IllegalArgumentException("count must be 1..500 and durationMs 1..60000");
        }
        String prefix = "burst-" + UUID.randomUUID().toString().substring(0, 8);
        for (int i = 1; i <= count; i++) {
            publish(new JobEvent(UUID.randomUUID(), prefix + "-" + i, durationMs, Instant.now()));
        }
        return new BurstResult(prefix, count, durationMs);
    }

    private void publish(JobEvent event) {
        try {
            rabbit.convertAndSend("", queueName, objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize job event", exception);
        }
    }

    public record CreateJobRequest(@NotBlank String jobKey, @Positive Integer durationMs) {
    }

    public record BurstResult(String prefix, int count, int durationMs) {
    }
}
