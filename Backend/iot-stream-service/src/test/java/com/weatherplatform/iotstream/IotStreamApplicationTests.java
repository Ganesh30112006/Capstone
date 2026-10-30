package com.weatherplatform.iotstream;

import com.weatherplatform.iotstream.ingest.RabbitTelemetrySource;
import com.weatherplatform.iotstream.ingest.TelemetrySource;
import com.weatherplatform.iotstream.service.TelemetryFanoutService;
import com.weatherplatform.iotstream.web.LiveTelemetryWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wiring smoke test. RabbitMQ listeners are not started (no broker in CI), but
 * the context — fan-out service, WebSocket handler, the default 'rabbit'
 * telemetry source and its beans — must assemble.
 */
@SpringBootTest
@TestPropertySource(properties = {
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "iot.stream.source=rabbit",
        "jwt.secret=test-secret-value-that-is-long-enough-for-hs256-signing",
        "jwt.internal-secret=test-internal-secret",
})
class IotStreamApplicationTests {

    @Autowired
    private TelemetrySource telemetrySource;
    @Autowired
    private TelemetryFanoutService fanoutService;
    @Autowired
    private LiveTelemetryWebSocketHandler webSocketHandler;

    @Test
    void contextLoadsWithTheRabbitSourceActive() {
        assertThat(telemetrySource).isInstanceOf(RabbitTelemetrySource.class);
        assertThat(telemetrySource.name()).isEqualTo("rabbit");
        assertThat(fanoutService).isNotNull();
        assertThat(webSocketHandler).isNotNull();
    }
}
