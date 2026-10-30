package com.weatherplatform.iotstream.ingest;

import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.config.RabbitStreamConfig;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.service.TelemetryFanoutService;
import com.weatherplatform.iotstream.web.LiveSessionRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * device-service published {@code device.registered},
 * {@code firmware.update.requested} and {@code firmware.update.completed} from
 * the start, and nothing was bound to any of them, so the broker discarded all
 * three. The rows behind them were always committed to PostgreSQL first, so
 * nothing durable was lost -- but the console was never told, and could only
 * find out by asking again.
 */
class DeviceEventMirrorListenerTest {

    private LiveSessionRegistry registry;
    private IotStreamProperties properties;
    private TelemetryFanoutService fanout;
    private DeviceEventMirrorListener listener;

    @BeforeEach
    void setUp() {
        registry = mock(LiveSessionRegistry.class);
        properties = new IotStreamProperties();
        fanout = new TelemetryFanoutService(
                mock(RabbitTemplate.class), registry, properties, new SimpleMeterRegistry());
        listener = new DeviceEventMirrorListener(fanout);
    }

    private Map<String, Object> body() {
        Map<String, Object> body = new HashMap<>();
        body.put("deviceUid", "ESP32-CHN-007");
        body.put("firmwareVersion", "v2.8.4");
        return body;
    }

    private StreamEnvelope mirrored() {
        ArgumentCaptor<StreamEnvelope> captor = ArgumentCaptor.forClass(StreamEnvelope.class);
        verify(registry).broadcast(captor.capture());
        return captor.getValue();
    }

    @Test
    @DisplayName("a registration reaches the console as a deviceRegistered frame")
    void registrationIsMirrored() {
        listener.onDeviceEvent(body(), RabbitStreamConfig.RK_DEVICE_REGISTERED);

        StreamEnvelope envelope = mirrored();
        assertThat(envelope.getType()).isEqualTo(StreamEnvelope.Type.deviceRegistered);
        assertThat(envelope.getDeviceUid()).isEqualTo("ESP32-CHN-007");
        assertThat(envelope.getSource()).isEqualTo("device");
        assertThat(envelope.getData()).containsEntry("firmwareVersion", "v2.8.4");
    }

    @Test
    @DisplayName("each firmware routing key maps to its own frame type")
    void firmwareKeysMapDistinctly() {
        assertThat(DeviceEventMirrorListener.typeOf(RabbitStreamConfig.RK_FIRMWARE_REQUESTED))
                .isEqualTo(StreamEnvelope.Type.firmwareRequested);
        assertThat(DeviceEventMirrorListener.typeOf(RabbitStreamConfig.RK_FIRMWARE_COMPLETED))
                .isEqualTo(StreamEnvelope.Type.firmwareCompleted);
        assertThat(DeviceEventMirrorListener.typeOf(RabbitStreamConfig.RK_DEVICE_REGISTERED))
                .isEqualTo(StreamEnvelope.Type.deviceRegistered);
    }

    @Test
    @DisplayName("an unrecognised routing key is dropped, not mirrored under a guessed type")
    void unknownKeyIsNotGuessed() {
        // Labelling an unknown event as one of the three would put a frame on the
        // console that claims something the message never said.
        assertThat(DeviceEventMirrorListener.typeOf("something.else")).isNull();
        assertThat(DeviceEventMirrorListener.typeOf(null)).isNull();

        listener.onDeviceEvent(body(), "something.else");

        verify(registry, never()).broadcast(org.mockito.ArgumentMatchers.any(StreamEnvelope.class));
    }

    @Test
    @DisplayName("device mirroring can be switched off without silencing AI events")
    void theTwoMirrorsAreIndependent() {
        properties.getFanout().setForwardDeviceEvents(false);

        listener.onDeviceEvent(body(), RabbitStreamConfig.RK_DEVICE_REGISTERED);
        verify(registry, never()).broadcast(org.mockito.ArgumentMatchers.any(StreamEnvelope.class));

        // The AI mirror is governed by its own flag and is untouched.
        assertThat(properties.getFanout().isForwardAiEvents()).isTrue();
        fanout.mirrorToClients(StreamEnvelope.builder()
                .type(StreamEnvelope.Type.anomaly).deviceUid("ESP32-CHN-007").build());
        verify(registry).broadcast(org.mockito.ArgumentMatchers.any(StreamEnvelope.class));
    }
}
