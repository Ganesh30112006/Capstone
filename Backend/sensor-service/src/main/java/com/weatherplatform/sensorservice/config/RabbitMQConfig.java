package com.weatherplatform.sensorservice.config;

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
 * Messaging topology for sensor-service.
 *
 * Sensor-service sits in the middle of the telemetry pipeline: it consumes raw
 * {@code iot.telemetry.received} from ingestion, persists it, and is the sole
 * publisher of the enriched {@code sensor.reading.created} that analytics,
 * alerting, the digital twin and the AI worker all subscribe to.
 *
 * Keeping the two routing keys distinct is what stops the pipeline looping
 * back on itself.
 */
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "weather.events";
    public static final String DLX_NAME = "weather.events.dlx";

    public static final String TELEMETRY_QUEUE = "sensor.telemetry.inbound";

    public static final String TELEMETRY_RECEIVED_ROUTING_KEY = "iot.telemetry.received";
    public static final String SENSOR_READING_ROUTING_KEY = "sensor.reading.created";
    public static final String SENSOR_FAILURE_ROUTING_KEY = "sensor.failure.detected";
    public static final String ANOMALY_DETECTED_ROUTING_KEY = "anomaly.detected";

    @Bean
    public TopicExchange weatherExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
    }

    @Bean
    public TopicExchange weatherDeadLetterExchange() {
        return new TopicExchange(DLX_NAME, true, false);
    }

    @Bean
    public Queue telemetryQueue() {
        return QueueBuilder.durable(TELEMETRY_QUEUE)
                .deadLetterExchange(DLX_NAME)
                .deadLetterRoutingKey(TELEMETRY_QUEUE + ".dlq")
                .build();
    }

    @Bean
    public Queue telemetryDlq() {
        return QueueBuilder.durable(TELEMETRY_QUEUE + ".dlq").build();
    }

    @Bean
    public Binding telemetryBinding(Queue telemetryQueue, TopicExchange weatherExchange) {
        return BindingBuilder.bind(telemetryQueue).to(weatherExchange).with(TELEMETRY_RECEIVED_ROUTING_KEY);
    }

    @Bean
    public Binding telemetryDlqBinding(Queue telemetryDlq, TopicExchange weatherDeadLetterExchange) {
        return BindingBuilder.bind(telemetryDlq).to(weatherDeadLetterExchange).with(TELEMETRY_QUEUE + ".dlq");
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
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
        template.setMandatory(true);
        template.setReturnsCallback(returned -> org.slf4j.LoggerFactory.getLogger(RabbitMQConfig.class)
                .error("Unroutable message to exchange={} routingKey={} reply={}",
                        returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }
}
