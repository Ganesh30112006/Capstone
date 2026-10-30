package com.weatherplatform.iotstream;

import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.config.JwtProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableScheduling;
import java.util.TimeZone;

/**
 * Real-time streaming branch off AWS IoT Core.
 *
 * <p>The platform has two paths out of IoT Core, and they are deliberately
 * independent:
 *
 * <pre>
 *   ESP32 --MQTT/TLS--> AWS IoT Core
 *       |
 *       |-- IoT Rule --> SQS --> iot-ingestion --> RabbitMQ --> sensor/analytics/alert/device
 *       |                                          (durable storage + deterministic alerting)
 *       |
 *       '-- MQTT/WSS  --> iot-stream (this service)
 *                             |-- RabbitMQ (iot.telemetry.stream) --> AI service: reports + predictions
 *                             '-- WebSocket (/ws/live) -------------> authorized admin browsers
 * </pre>
 *
 * If this service is slow or down, nothing on the storage or alerting path is
 * affected — the SQS branch keeps running. That is the whole reason it is a
 * separate service rather than another consumer inside iot-ingestion.
 */
@SpringBootApplication
@EnableScheduling
@EnableConfigurationProperties({IotStreamProperties.class, JwtProperties.class})
public class IotStreamApplication {

    public static void main(String[] args) {
        /*
         * Every timestamp this platform stores is UTC, and that has to be true
         * before Spring starts rather than by convention afterwards.
         *
         * Two conventions were coexisting. Telemetry arrives from
         * iot-ingestion-service as a LocalDateTime already normalised to UTC, while
         * every LocalDateTime.now() and @CreationTimestamp in the services produced
         * the JVM's local time. With hibernate.jdbc.time_zone=UTC set, Hibernate
         * then converted both on the way to a `timestamp without time zone` column:
         * the local values happened to land on the correct UTC instant, and the
         * already-UTC values were shifted a second time. On a host in IST a reading
         * taken at 07:55 UTC was stored as 02:25.
         *
         * It only looked correct because containers usually default to UTC, which
         * makes the conversion a no-op. Pinning the zone here makes that assumption
         * explicit and true wherever the service runs -- container, laptop or CI.
         */
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(IotStreamApplication.class, args);
    }
}
