package com.weatherplatform.alertservice.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Messaging topology for alert-service.
 *
 * Alerting subscribes to four independent signals — threshold breaches, sensor
 * failures, AI anomalies, and offline devices — on separate queues, so a
 * backlog or poison message on one stream cannot stall the others.
 *
 * Every queue dead-letters; with retry configured in application.yml, a message
 * that keeps failing is parked for inspection rather than lost or looping.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "weather.events";
    public static final String DLX_NAME = "weather.events.dlx";

    public static final String QUEUE_SENSOR_READINGS = "alert.sensor.readings";
    public static final String QUEUE_SENSOR_FAILURES = "alert.sensor.failures";
    public static final String QUEUE_ANOMALIES = "alert.anomalies";
    public static final String QUEUE_DEVICE_OFFLINE = "alert.device.offline";

    public static final String ROUTING_KEY_SENSOR_READING = "sensor.reading.created";
    public static final String ROUTING_KEY_SENSOR_FAILURE = "sensor.failure.detected";
    public static final String ROUTING_KEY_ANOMALY_DETECTED = "anomaly.detected";
    public static final String ROUTING_KEY_DEVICE_OFFLINE = "device.offline";

    private static final List<String> ALL_QUEUES = List.of(
            QUEUE_SENSOR_READINGS, QUEUE_SENSOR_FAILURES, QUEUE_ANOMALIES, QUEUE_DEVICE_OFFLINE);

    @Bean
    public TopicExchange weatherExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public TopicExchange weatherDeadLetterExchange() {
        return new TopicExchange(DLX_NAME, true, false);
    }

    private static Queue consumerQueue(String name) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(DLX_NAME)
                .deadLetterRoutingKey(name + ".dlq")
                .build();
    }

    @Bean
    public Queue alertSensorReadingsQueue() {
        return consumerQueue(QUEUE_SENSOR_READINGS);
    }

    @Bean
    public Queue alertSensorFailuresQueue() {
        return consumerQueue(QUEUE_SENSOR_FAILURES);
    }

    @Bean
    public Queue alertAnomaliesQueue() {
        return consumerQueue(QUEUE_ANOMALIES);
    }

    @Bean
    public Queue alertDeviceOfflineQueue() {
        return consumerQueue(QUEUE_DEVICE_OFFLINE);
    }

    /**
     * One dead-letter queue per source queue, plus its binding.
     *
     * Declared as a group so adding a queue to {@link #ALL_QUEUES} automatically
     * gets a matching DLQ — a poison message can always be traced back to the
     * stream it came from.
     */
    @Bean
    public Declarables alertDeadLetterQueues() {
        TopicExchange dlx = new TopicExchange(DLX_NAME, true, false);

        List<Declarable> declarables = new ArrayList<>();
        for (String source : ALL_QUEUES) {
            Queue dlq = QueueBuilder.durable(source + ".dlq").build();
            declarables.add(dlq);
            declarables.add(BindingBuilder.bind(dlq).to(dlx).with(source + ".dlq"));
        }
        return new Declarables(declarables);
    }

    @Bean
    public Binding alertSensorReadingsBinding(Queue alertSensorReadingsQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(alertSensorReadingsQueue).to(weatherExchange).with(ROUTING_KEY_SENSOR_READING);
    }

    @Bean
    public Binding alertSensorFailuresBinding(Queue alertSensorFailuresQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(alertSensorFailuresQueue).to(weatherExchange).with(ROUTING_KEY_SENSOR_FAILURE);
    }

    @Bean
    public Binding alertAnomaliesBinding(Queue alertAnomaliesQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(alertAnomaliesQueue).to(weatherExchange).with(ROUTING_KEY_ANOMALY_DETECTED);
    }

    @Bean
    public Binding alertDeviceOfflineBinding(Queue alertDeviceOfflineQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(alertDeviceOfflineQueue).to(weatherExchange).with(ROUTING_KEY_DEVICE_OFFLINE);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        // The AI service is Python and emits offset-bearing ISO timestamps; the
        // stock LocalDateTime reader rejects those. See UtcLocalDateTimeDeserializer.
        JavaTimeModule timeModule = new JavaTimeModule();
        timeModule.addDeserializer(LocalDateTime.class, new UtcLocalDateTimeDeserializer());

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(timeModule)
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
