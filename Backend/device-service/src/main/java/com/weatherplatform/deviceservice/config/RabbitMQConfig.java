package com.weatherplatform.deviceservice.config;

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
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging topology for device-service.
 *
 * Every consumer queue dead-letters to {@link #DLX_NAME}. Combined with
 * listener retry + {@code default-requeue-rejected: false} in application.yml,
 * a message that keeps failing is parked on its own DLQ for inspection rather
 * than being silently dropped or redelivered forever.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "weather.events";
    public static final String DLX_NAME = "weather.events.dlx";

    public static final String DIGITAL_TWIN_QUEUE = "device.digitaltwin.sensor.readings";
    public static final String HEARTBEAT_QUEUE = "device.heartbeat.inbound";

    public static final String ROUTING_KEY_SENSOR_READING = "sensor.reading.created";
    public static final String ROUTING_KEY_DEVICE_REGISTERED = "device.registered";
    public static final String ROUTING_KEY_DEVICE_HEARTBEAT = "device.heartbeat";
    public static final String ROUTING_KEY_DEVICE_OFFLINE = "device.offline";
    public static final String ROUTING_KEY_FIRMWARE_REQUESTED = "firmware.update.requested";
    public static final String ROUTING_KEY_FIRMWARE_COMPLETED = "firmware.update.completed";

    @Bean
    public TopicExchange weatherExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public TopicExchange weatherDeadLetterExchange() {
        return new TopicExchange(DLX_NAME, true, false);
    }

    private static Queue durableQueueWithDlq(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DLX_NAME)
                .deadLetterRoutingKey(name + ".dlq")
                .build();
    }

    @Bean
    public Queue digitalTwinQueue() {
        return durableQueueWithDlq(DIGITAL_TWIN_QUEUE);
    }

    @Bean
    public Queue digitalTwinDlq() {
        return QueueBuilder.durable(DIGITAL_TWIN_QUEUE + ".dlq").build();
    }

    @Bean
    public Queue heartbeatQueue() {
        return durableQueueWithDlq(HEARTBEAT_QUEUE);
    }

    @Bean
    public Queue heartbeatDlq() {
        return QueueBuilder.durable(HEARTBEAT_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding digitalTwinBinding(Queue digitalTwinQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(digitalTwinQueue).to(weatherExchange).with(ROUTING_KEY_SENSOR_READING);
    }

    @Bean
    public Binding heartbeatBinding(Queue heartbeatQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(heartbeatQueue).to(weatherExchange).with(ROUTING_KEY_DEVICE_HEARTBEAT);
    }

    @Bean
    public Binding digitalTwinDlqBinding(Queue digitalTwinDlq, TopicExchange weatherDeadLetterExchange) {
        return BindingBuilder.bind(digitalTwinDlq).to(weatherDeadLetterExchange).with(DIGITAL_TWIN_QUEUE + ".dlq");
    }

    @Bean
    public Binding heartbeatDlqBinding(Queue heartbeatDlq, TopicExchange weatherDeadLetterExchange) {
        return BindingBuilder.bind(heartbeatDlq).to(weatherDeadLetterExchange).with(HEARTBEAT_QUEUE + ".dlq");
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

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        // Surface unroutable messages instead of letting the broker discard them.
        template.setMandatory(true);
        template.setReturnsCallback(returned -> org.slf4j.LoggerFactory.getLogger(RabbitMQConfig.class)
                .error("Unroutable message to exchange={} routingKey={} reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
