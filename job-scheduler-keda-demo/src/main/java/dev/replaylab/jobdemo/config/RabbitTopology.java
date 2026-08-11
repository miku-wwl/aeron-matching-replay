package dev.replaylab.jobdemo.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.ApplicationRunner;

@Configuration
public class RabbitTopology {

    @Bean
    Declarables jobTopology(
            @Value("${app.rabbit.exchange}") String exchangeName,
            @Value("${app.rabbit.ready-queue}") String readyQueueName,
            @Value("${app.rabbit.dlq}") String dlqName,
            @Value("${app.rabbit.ready-routing-key}") String readyRoutingKey,
            @Value("${app.rabbit.dlq-routing-key}") String dlqRoutingKey) {

        DirectExchange exchange = new DirectExchange(exchangeName, true, false);
        Queue readyQueue = QueueBuilder.durable(readyQueueName)
                .quorum()
                .deadLetterExchange(exchangeName)
                .deadLetterRoutingKey(dlqRoutingKey)
                .build();
        Queue deadLetterQueue = QueueBuilder.durable(dlqName).quorum().build();

        return new Declarables(exchange, readyQueue, deadLetterQueue,
                BindingBuilder.bind(readyQueue).to(exchange).with(readyRoutingKey),
                BindingBuilder.bind(deadLetterQueue).to(exchange).with(dlqRoutingKey));
    }

    @Bean
    Jackson2JsonMessageConverter rabbitMessageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        converter.setCreateMessageIds(true);
        return converter;
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory,
                                  Jackson2JsonMessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }

    @Bean
    ApplicationRunner initializeRabbitTopology(RabbitAdmin rabbitAdmin) {
        return arguments -> rabbitAdmin.initialize();
    }
}
