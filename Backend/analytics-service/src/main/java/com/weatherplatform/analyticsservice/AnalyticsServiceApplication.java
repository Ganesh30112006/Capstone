package com.weatherplatform.analyticsservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import java.util.TimeZone;

@SpringBootApplication
public class AnalyticsServiceApplication {
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
        SpringApplication.run(AnalyticsServiceApplication.class, args);
    }
}
