package com.weatherplatform.analyticsservice.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging topology for analytics-service.
 *
 * Analytics aggregates on the way in: each sensor reading folds into a running
 * daily bucket, so reports read a handful of rows instead of scanning raw
 * telemetry. That makes this consumer latency-tolerant but loss-sensitive — a
 * dropped event silently skews an average — hence the dead-letter queue.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "weather.events";
    public static final String DLX_NAME = "weather.events.dlx";

    public static final String QUEUE_NAME = "analytics.sensor.readings";
    public static final String DLQ_NAME = QUEUE_NAME + ".dlq";

    public static final String ROUTING_KEY = "sensor.reading.created";

    @Bean
    public TopicExchange weatherExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public TopicExchange weatherDeadLetterExchange() {
        return new TopicExchange(DLX_NAME, true, false);
    }

    @Bean
    public Queue analyticsQueue() {
        return QueueBuilder.durable(QUEUE_NAME)
                .deadLetterExchange(DLX_NAME)
                .deadLetterRoutingKey(DLQ_NAME)
                .build();
    }

    @Bean
    public Queue analyticsDlq() {
        return QueueBuilder.durable(DLQ_NAME).build();
    }

    @Bean
    public Binding analyticsBinding(Queue analyticsQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(analyticsQueue).to(weatherExchange).with(ROUTING_KEY);
    }

    @Bean
    public Binding analyticsDlqBinding(Queue analyticsDlq, TopicExchange weatherDeadLetterExchange) {
        return BindingBuilder.bind(analyticsDlq).to(weatherDeadLetterExchange).with(DLQ_NAME);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Producers evolve independently; an unknown field must not poison a consumer.
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                /*
                 * ISO-8601 strings, not numeric arrays.
                 *
                 * Jackson's default writes a LocalDateTime as [2026,9,16,8,6,32,695122000].
                 * Java reads that back happily, so it survived every Java-to-Java test --
                 * and broke the moment a Python consumer read it: the AI service called
                 * .replace() on what it expected to be a timestamp string and rejected
                 * every reading to its dead-letter queue. On a polyglot bus the wire
                 * format has to be the interoperable one.
                 */
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setSerializationInclusion(JsonInclude.Include.NON_NULL);
        return new Jackson2JsonMessageConverter(mapper);
    }
}
