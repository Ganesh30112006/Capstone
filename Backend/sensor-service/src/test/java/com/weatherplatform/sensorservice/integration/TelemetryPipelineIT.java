package com.weatherplatform.sensorservice.integration;

import com.weatherplatform.sensorservice.config.RabbitMQConfig;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.event.TelemetryReceivedEvent;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the real telemetry path end to end inside this service:
 *
 * <pre>
 * RabbitMQ (iot.telemetry.received) -> TelemetryIngestionListener
 *     -> SensorDataService -> PostgreSQL (Flyway-migrated)
 *     -> RabbitMQ (sensor.reading.created)
 * </pre>
 *
 * Unit tests mock the broker and the repository, so they cannot catch the
 * failures that actually happen here: a message that will not deserialise into
 * {@link TelemetryReceivedEvent}, a Flyway migration that does not match the
 * entity mapping, a unique constraint that does not hold, or a downstream event
 * published with a routing key nothing is bound to. Those only appear against
 * a real broker and a real database, which is what these containers provide.
 *
 * Skipped rather than failed when no Docker daemon is available, so the build
 * still runs on a machine without one.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetryPipelineIT {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

    @Container
    @ServiceConnection
    static final RabbitMQContainer RABBIT =
            new RabbitMQContainer(DockerImageName.parse("rabbitmq:3.13-management-alpine"));

    /** Stands in for analytics, alerting, the digital twin and the AI worker. */
    private static final String DOWNSTREAM_QUEUE = "test.sensor.reading.created";
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private SensorDataRepository sensorDataRepository;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @BeforeEach
    void bindDownstreamQueue() {
        sensorDataRepository.deleteAll();

        Queue queue = QueueBuilder.nonDurable(DOWNSTREAM_QUEUE).autoDelete().build();
        TopicExchange exchange = new TopicExchange(RabbitMQConfig.EXCHANGE_NAME, true, false);
        Binding binding = BindingBuilder.bind(queue).to(exchange).with(RabbitMQConfig.SENSOR_READING_ROUTING_KEY);

        amqpAdmin.declareQueue(queue);
        amqpAdmin.declareBinding(binding);
        amqpAdmin.purgeQueue(DOWNSTREAM_QUEUE, false);
    }

    @Test
    @DisplayName("telemetry published to the bus is persisted to PostgreSQL")
    void storesTelemetryArrivingOnTheBus() {
        String messageId = UUID.randomUUID().toString();
        TelemetryReceivedEvent event = telemetry(messageId, "ESP32-IT-001", new BigDecimal("21.50"));

        publish(event);

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Optional<SensorData> stored = sensorDataRepository.findByMessageId(messageId);
            assertThat(stored).isPresent();
            assertThat(stored.get().getDeviceUid()).isEqualTo("ESP32-IT-001");
            // Round-tripped through JSON, the broker, JPA and NUMERIC(5,2).
            assertThat(stored.get().getTemperature()).isEqualByComparingTo("21.50");
            assertThat(stored.get().getAirQuality()).isEqualTo(42);
            assertThat(stored.get().getRain()).isFalse();
        });
    }

    @Test
    @DisplayName("a redelivered message with the same id stores exactly one reading")
    void isIdempotentAcrossRedelivery() {
        String messageId = UUID.randomUUID().toString();
        TelemetryReceivedEvent event = telemetry(messageId, "ESP32-IT-002", new BigDecimal("18.25"));

        // SQS guarantees at-least-once, so the same reading genuinely arrives twice.
        publish(event);
        await().atMost(TIMEOUT).until(() -> sensorDataRepository.findByMessageId(messageId).isPresent());
        publish(event);

        await().during(Duration.ofSeconds(3)).atMost(TIMEOUT).untilAsserted(() ->
                assertThat(sensorDataRepository.countByDeviceUid("ESP32-IT-002"))
                        .as("a duplicate delivery must not create a second reading")
                        .isEqualTo(1));
    }

    @Test
    @DisplayName("a stored reading is republished for analytics, alerting and the AI worker")
    void republishesTheEnrichedReadingDownstream() {
        String messageId = UUID.randomUUID().toString();
        publish(telemetry(messageId, "ESP32-IT-003", new BigDecimal("25.00")));

        await().atMost(TIMEOUT).untilAsserted(() -> {
            Object downstream = rabbitTemplate.receiveAndConvert(DOWNSTREAM_QUEUE);
            assertThat(downstream)
                    .as("nothing was published to %s; downstream consumers would never see this reading",
                            RabbitMQConfig.SENSOR_READING_ROUTING_KEY)
                    .isNotNull();
            assertThat(downstream.toString()).contains("ESP32-IT-003");
        });
    }

    @Test
    @DisplayName("telemetry with no device identity is rejected, not stored")
    void rejectsTelemetryWithoutADeviceUid() {
        TelemetryReceivedEvent orphan = telemetry(UUID.randomUUID().toString(), null, new BigDecimal("19.00"));

        publish(orphan);

        // Rejected outright rather than retried, so it reaches the dead-letter
        // queue immediately and never reaches the table.
        await().atMost(TIMEOUT).untilAsserted(() ->
                assertThat(amqpAdmin.getQueueInfo(RabbitMQConfig.TELEMETRY_QUEUE + ".dlq"))
                        .isNotNull()
                        .extracting(info -> info.getMessageCount())
                        .isEqualTo(1));

        assertThat(sensorDataRepository.count()).isZero();
    }

    private void publish(TelemetryReceivedEvent event) {
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.EXCHANGE_NAME,
                RabbitMQConfig.TELEMETRY_RECEIVED_ROUTING_KEY,
                event);
    }

    private TelemetryReceivedEvent telemetry(String messageId, String deviceUid, BigDecimal temperature) {
        return TelemetryReceivedEvent.builder()
                .messageId(messageId)
                .deviceUid(deviceUid)
                .temperature(temperature)
                .humidity(new BigDecimal("61.00"))
                .airQuality(42)
                .pressure(new BigDecimal("1013.20"))
                .rain(false)
                .oxygen(new BigDecimal("20.90"))
                .latitude(17.385)
                .longitude(78.4867)
                .recordedAt(LocalDateTime.now())
                .build();
    }
}
