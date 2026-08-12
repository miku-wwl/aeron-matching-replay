package dev.replaylab.jobdemo;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {

    @Bean
    Queue jobQueue(@Value("${app.rabbit.ready-queue}") String queueName) {
        return QueueBuilder.durable(queueName).quorum().build();
    }
}
