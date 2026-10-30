package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.dto.DeviceHeartbeatRequest;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.event.DeviceHeartbeatEvent;
import com.weatherplatform.deviceservice.mapper.DeviceMapper;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A heartbeat that arrived from the bus must not be published back onto it.
 *
 * The listener for {@code device.heartbeat} called {@code processHeartbeat},
 * which ends by publishing a {@code DeviceHeartbeatEvent} to that same routing
 * key -- so the listener consumed its own output and every heartbeat became a
 * self-sustaining stream. It was invisible in unit tests, invisible in Compose
 * smoke runs, and obvious within minutes of a real fleet reporting: roughly
 * sixty genuine heartbeats had become sixty thousand messages and were still
 * doubling, which buried live telemetry under heartbeat frames and pinned the
 * broker.
 *
 * The split that fixes it is the thing worth protecting: applying a heartbeat
 * and announcing one are separate operations, and only the caller that
 * originated the heartbeat announces it.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DeviceHeartbeatLoopTest {

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DeviceMapper deviceMapper;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private DeviceServiceImpl deviceService;

    @BeforeEach
    void setUp() {
        deviceService = new DeviceServiceImpl(deviceRepository, deviceMapper, rabbitTemplate);
        ReflectionTestUtils.setField(deviceService, "offlineThresholdMinutes", 5);

        Device device = Device.builder()
                .id(1L)
                .deviceUid("ESP32-001")
                .deviceName("North Ridge")
                .status(DeviceStatus.ONLINE)
                .lastSeenAt(LocalDateTime.now())
                .build();
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
        when(deviceRepository.save(any(Device.class))).thenAnswer(i -> i.getArgument(0));
    }

    private static DeviceHeartbeatRequest heartbeat() {
        return DeviceHeartbeatRequest.builder()
                .deviceUid("ESP32-001")
                .batteryLevel(84)
                .wifiSignalStrength(-58)
                .firmwareVersion("1.4.2")
                .timestamp(LocalDateTime.now())
                .build();
    }

    @Test
    @DisplayName("applying a heartbeat from the bus publishes nothing")
    void applyDoesNotRepublish() {
        deviceService.applyHeartbeat("ESP32-001", heartbeat());

        // One publish here is one extra heartbeat on the queue this listener
        // reads, and the amplification is unbounded from there.
        verify(rabbitTemplate, never())
                .convertAndSend(anyString(), anyString(), any(DeviceHeartbeatEvent.class));
    }

    @Test
    @DisplayName("applying a heartbeat still records battery, signal and firmware")
    void applyStillUpdatesTheDevice() {
        deviceService.applyHeartbeat("ESP32-001", heartbeat());

        Device saved = deviceRepository.findByDeviceUid("ESP32-001").orElseThrow();
        assertThat(saved.getBatteryLevel()).isEqualTo(84);
        assertThat(saved.getWifiSignalStrength()).isEqualTo(-58);
        assertThat(saved.getFirmwareVersion()).isEqualTo("1.4.2");
        assertThat(saved.getStatus()).isEqualTo(DeviceStatus.ONLINE);
        verify(deviceRepository).save(any(Device.class));
    }

    @Test
    @DisplayName("a heartbeat that arrived over HTTP is still announced once")
    void processAnnouncesExactlyOnce() {
        deviceService.processHeartbeat("ESP32-001", heartbeat());

        // The REST path is the origin of this heartbeat, so the rest of the
        // platform has not seen it and does need telling -- exactly once.
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY_DEVICE_HEARTBEAT),
                any(DeviceHeartbeatEvent.class));
    }

    @Test
    @DisplayName("a broker outage does not lose the heartbeat that was applied")
    void publishFailureDoesNotDiscardTheUpdate() {
        doThrow(new RuntimeException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(DeviceHeartbeatEvent.class));

        deviceService.processHeartbeat("ESP32-001", heartbeat());

        // The station really did report; failing to tell anyone must not undo that.
        assertThat(deviceRepository.findByDeviceUid("ESP32-001").orElseThrow().getBatteryLevel())
                .isEqualTo(84);
    }
}
