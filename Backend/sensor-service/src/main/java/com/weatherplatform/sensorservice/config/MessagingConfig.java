package com.weatherplatform.sensorservice.config;

import org.slf4j.MDC;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.amqp.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.UUID;

/**
 * Restores the correlation ID onto the consumer thread.
 *
 * Without this, a request traced through the gateway and HTTP hops loses its
 * identity the moment work crosses into RabbitMQ, which is exactly where async
 * failures are hardest to diagnose.
 */
@Configuration
public class MessagingConfig {

    public static final String CORRELATION_HEADER = "X-Correlation-ID";
    private static final String CORRELATION_MDC = "correlationId";

    @Bean
    public MessagePostProcessor correlationIdRestoringPostProcessor() {
        return message -> {
            Object header = message.getMessageProperties().getHeader(CORRELATION_HEADER);
            // Always write a value — never leave a stale ID from the previous message
            // on this pooled consumer thread.
            MDC.put(CORRELATION_MDC, header != null ? header.toString() : UUID.randomUUID().toString());
            return message;
        };
    }

    /**
     * Rebuilds the listener factory so the post-processor applies, while
     * {@code configurer} keeps every retry/concurrency setting from application.yml.
     */
    @Bean
    @Primary
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer,
            ConnectionFactory connectionFactory,
            MessageConverter messageConverter,
            MessagePostProcessor correlationIdRestoringPostProcessor) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(messageConverter);
        factory.setAfterReceivePostProcessors(correlationIdRestoringPostProcessor);
        return factory;
    }
}
