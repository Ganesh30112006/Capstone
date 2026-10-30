package com.weatherplatform.iotstream.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Messaging topology for the streaming branch.
 *
 * <p>Outbound: live readings on {@code iot.telemetry.stream} /
 * {@code iot.heartbeat.stream}, consumed by the AI service for forecasting and
 * reporting. These are distinct from {@code iot.telemetry.received} (the SQS
 * branch's key) so the two pipelines never cross.
 *
 * <p>Inbound: AI output ({@code prediction.generated}, {@code report.generated},
 * {@code anomaly.detected}) which this service forwards onto the client
 * WebSocket. An exclusive, auto-delete, non-durable queue per instance — if
 * this service is down, those events simply are not mirrored to browsers; they
 * are still delivered to their durable consumers (analytics, alerting).
 */
@Slf4j
@Configuration
public class RabbitStreamConfig {

    public static final String EXCHANGE = "weather.events";
    public static final String DLX = "weather.events.dlx";

    public static final String RK_TELEMETRY_STREAM = "iot.telemetry.stream";
    public static final String RK_HEARTBEAT_STREAM = "iot.heartbeat.stream";

    // Inbound: raw telemetry from the SQS branch, used only when
    // iot.stream.source=rabbit (local/CI). See RabbitTelemetrySource.
    public static final String RK_TELEMETRY_RECEIVED = "iot.telemetry.received";
    public static final String RK_DEVICE_HEARTBEAT = "device.heartbeat";
    public static final String QUEUE_SOURCE_TELEMETRY = "iot.stream.source.telemetry";
    public static final String QUEUE_SOURCE_HEARTBEAT = "iot.stream.source.heartbeat";

    // Inbound: AI output mirrored to browsers.
    public static final String RK_PREDICTION = "prediction.generated";
    public static final String RK_REPORT = "report.generated";
    public static final String RK_ANOMALY = "anomaly.detected";
    public static final String QUEUE_AI_MIRROR = "iot.stream.ai-events";

    // Inbound: device lifecycle from device-service, mirrored to browsers.
    public static final String RK_DEVICE_REGISTERED = "device.registered";
    public static final String RK_FIRMWARE_REQUESTED = "firmware.update.requested";
    public static final String RK_FIRMWARE_COMPLETED = "firmware.update.completed";
    public static final String QUEUE_DEVICE_MIRROR = "iot.stream.device-events";

    @Bean
    public TopicExchange weatherExchange() {
        return new TopicExchange(EXCHANGE, true, false);
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
        // Live-stream publishes are best-effort: the SQS branch is the durable
        // path. A dropped stream frame costs one skipped forecast tick, not a
        // lost reading, so a failed publish is logged, not retried to death.
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.warn(
                "Unroutable stream frame exchange={} rk={} reply={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        template.setConfirmCallback((correlation, ack, cause) -> {
            if (!ack) {
                log.warn("Broker did not confirm stream publish: {}", cause);
            }
        });
        return template;
    }

    // ----- inbound: local/CI telemetry source (source=rabbit) -----

    @Bean
    public Queue streamSourceTelemetryQueue() {
        return QueueBuilder.durable(QUEUE_SOURCE_TELEMETRY)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(QUEUE_SOURCE_TELEMETRY + ".dlq")
                .build();
    }

    @Bean
    public Queue streamSourceHeartbeatQueue() {
        return QueueBuilder.durable(QUEUE_SOURCE_HEARTBEAT)
                .deadLetterExchange(DLX)
                .deadLetterRoutingKey(QUEUE_SOURCE_HEARTBEAT + ".dlq")
                .build();
    }

    /*
     * The dead-letter side of the two source queues.
     *
     * Both queues named a dead-letter routing key from the start, but nothing
     * declared the queues behind those names or bound them to the dead-letter
     * exchange -- so a rejected message was routed to a key with no binding and
     * the broker discarded it. The rejection was logged, which made it look
     * handled; the payload itself was gone, and there was nothing left to
     * inspect or replay. Every other queue on this platform declares queue,
     * dead-letter queue and dead-letter binding together, and these two now do
     * the same.
     */

    @Bean
    public TopicExchange weatherDeadLetterExchange() {
        return new TopicExchange(DLX, true, false);
    }

    @Bean
    public Queue streamSourceTelemetryDlq() {
        return QueueBuilder.durable(QUEUE_SOURCE_TELEMETRY + ".dlq").build();
    }

    @Bean
    public Queue streamSourceHeartbeatDlq() {
        return QueueBuilder.durable(QUEUE_SOURCE_HEARTBEAT + ".dlq").build();
    }

    @Bean
    public Binding streamSourceTelemetryDlqBinding() {
        return BindingBuilder.bind(streamSourceTelemetryDlq())
                .to(weatherDeadLetterExchange())
                .with(QUEUE_SOURCE_TELEMETRY + ".dlq");
    }

    @Bean
    public Binding streamSourceHeartbeatDlqBinding() {
        return BindingBuilder.bind(streamSourceHeartbeatDlq())
                .to(weatherDeadLetterExchange())
                .with(QUEUE_SOURCE_HEARTBEAT + ".dlq");
    }

    @Bean
    public Binding streamSourceTelemetryBinding() {
        return BindingBuilder.bind(streamSourceTelemetryQueue()).to(weatherExchange()).with(RK_TELEMETRY_RECEIVED);
    }

    @Bean
    public Binding streamSourceHeartbeatBinding() {
        return BindingBuilder.bind(streamSourceHeartbeatQueue()).to(weatherExchange()).with(RK_DEVICE_HEARTBEAT);
    }

    // ----- inbound: AI events mirrored to WebSocket clients -----

    @Bean
    public Queue aiMirrorQueue() {
        // Non-durable, exclusive to this instance, auto-deleted on disconnect:
        // a missed mirror frame is cosmetic, and we do not want a queue growing
        // while no browser is connected.
        return QueueBuilder.nonDurable(QUEUE_AI_MIRROR).autoDelete().exclusive().build();
    }

    @Bean
    public Binding aiMirrorPredictionBinding() {
        return BindingBuilder.bind(aiMirrorQueue()).to(weatherExchange()).with(RK_PREDICTION);
    }

    @Bean
    public Binding aiMirrorReportBinding() {
        return BindingBuilder.bind(aiMirrorQueue()).to(weatherExchange()).with(RK_REPORT);
    }

    @Bean
    public Binding aiMirrorAnomalyBinding() {
        return BindingBuilder.bind(aiMirrorQueue()).to(weatherExchange()).with(RK_ANOMALY);
    }

    // ----- inbound: device lifecycle mirrored to WebSocket clients -----

    /*
     * device-service publishes device.registered, firmware.update.requested and
     * firmware.update.completed, and until now nothing anywhere on the platform
     * was bound to any of them -- the broker accepted each message and dropped
     * it. The publishes were not even reported as undeliverable, because the
     * returns callback that exists to report exactly that was never armed.
     *
     * The durable record was never at risk: device-service commits the device
     * and the firmware-update row to PostgreSQL before it publishes, and the
     * REST API serves them. What was missing was the live notification, so the
     * console could not show a station appearing or a rollout finishing without
     * being re-polled.
     *
     * This queue carries the same properties as the AI mirror and for the same
     * reason: it is a courtesy copy for whoever is watching. Non-durable,
     * exclusive and auto-deleted, so nothing accumulates while no browser is
     * connected, and the authoritative copy stays in the database either way.
     */

    @Bean
    public Queue deviceMirrorQueue() {
        return QueueBuilder.nonDurable(QUEUE_DEVICE_MIRROR).autoDelete().exclusive().build();
    }

    @Bean
    public Binding deviceMirrorRegisteredBinding() {
        return BindingBuilder.bind(deviceMirrorQueue()).to(weatherExchange()).with(RK_DEVICE_REGISTERED);
    }

    @Bean
    public Binding deviceMirrorFirmwareRequestedBinding() {
        return BindingBuilder.bind(deviceMirrorQueue()).to(weatherExchange()).with(RK_FIRMWARE_REQUESTED);
    }

    @Bean
    public Binding deviceMirrorFirmwareCompletedBinding() {
        return BindingBuilder.bind(deviceMirrorQueue()).to(weatherExchange()).with(RK_FIRMWARE_COMPLETED);
    }
}
