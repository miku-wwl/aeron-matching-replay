package dev.replaylab.jobdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "app.role=api")
@Testcontainers(disabledWithoutDocker = true)
class CoreFlowIntegrationTest {

    @Container
    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Autowired JobApi api;
    @Autowired RabbitTemplate rabbit;
    @Autowired ObjectMapper objectMapper;

    @Test
    void apiPublishesJobEventDirectlyToRabbitMq() throws Exception {
        JobEvent event = api.create(new JobApi.CreateJobRequest("direct-event", 10));

        Object message = rabbit.receiveAndConvert("demo.jobs.ready", 5_000);
        assertThat(message).isInstanceOf(String.class);
        JsonNode received = objectMapper.readTree((String) message);
        assertThat(received.get("eventId").asText()).isEqualTo(event.eventId().toString());
        assertThat(received.get("jobKey").asText()).isEqualTo("direct-event");
        assertThat(received.get("durationMs").asInt()).isEqualTo(10);
    }
}
