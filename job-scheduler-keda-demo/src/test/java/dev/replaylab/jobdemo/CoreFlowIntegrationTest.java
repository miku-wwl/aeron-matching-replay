package dev.replaylab.jobdemo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "app.role=api")
@AutoConfigureMockMvc
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
    @Autowired MockMvc mockMvc;

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

    @Test
    void apiRejectsDurationAboveMaximum() throws Exception {
        mockMvc.perform(post("/api/jobs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jobKey\":\"too-long\",\"durationMs\":60001}"))
                .andExpect(status().isBadRequest());
    }
}
