package com.weatherplatform.iotingestion.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Publishing topology for the ingestion service.
 *
 * This service is a pure producer: it reads from SQS and publishes onto the
 * internal bus. Publisher confirms are enabled because an unconfirmed publish
 * here means telemetry that was acknowledged to SQS but never reached the
 * platform — silent data loss.
 */
@Slf4j
@Configuration
public class RabbitMQConfig {

    public static final String EXCHANGE_NAME = "weather.events";

    /**
     * Raw telemetry, consumed by sensor-service. Distinct from
     * {@code sensor.reading.created}, which sensor-service publishes only after
     * persisting and enriching — keeping them separate prevents a routing loop.
     */
    public static final String ROUTING_KEY_TELEMETRY_RECEIVED = "iot.telemetry.received";
    public static final String ROUTING_KEY_DEVICE_HEARTBEAT = "device.heartbeat";

    @Bean
    public TopicExchange weatherExchange() {
        return new TopicExchange(EXCHANGE_NAME, true, false);
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
        if (connectionFactory instanceof CachingConnectionFactory caching) {
            caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            caching.setPublisherReturns(true);
        }

        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);

        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.error("Broker did not confirm publish (correlation={}): {}",
                        correlation != null ? correlation.getId() : "n/a", cause);
            }
        });
        template.setReturnsCallback(returned -> log.error(
                "Unroutable message to exchange={} routingKey={} reply={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));

        return template;
    }
}
