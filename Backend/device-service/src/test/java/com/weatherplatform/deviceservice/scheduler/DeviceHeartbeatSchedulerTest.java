package com.weatherplatform.deviceservice.scheduler;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.event.DeviceOfflineEvent;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Stale-device detection is the platform's only answer to a station that stops
 * reporting without saying goodbye.
 *
 * If this scheduler silently stops working, every dead station stays ONLINE
 * forever and the console reports a fully reporting fleet -- the same
 * healthy-looking-failure the rest of this codebase has been audited for, but
 * originating in the data rather than the display.
 */
@ExtendWith(MockitoExtension.class)
class DeviceHeartbeatSchedulerTest {

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;

    private DeviceHeartbeatScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new DeviceHeartbeatScheduler(deviceRepository, rabbitTemplate);
        ReflectionTestUtils.setField(scheduler, "offlineThresholdMinutes", 5);
    }

    private static Device online(String uid, LocalDateTime lastSeen) {
        Device device = new Device();
        device.setDeviceUid(uid);
        device.setDeviceName("Station " + uid);
        device.setStatus(DeviceStatus.ONLINE);
        device.setLastSeenAt(lastSeen);
        return device;
    }

    @Test
    @DisplayName("marks a station that has stopped reporting as OFFLINE")
    void staleDeviceGoesOffline() {
        Device stale = online("ESP32-001", LocalDateTime.now().minusMinutes(30));
        when(deviceRepository.findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any()))
                .thenReturn(List.of(stale));

        scheduler.checkDeviceHeartbeats();

        assertThat(stale.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
        verify(deviceRepository).save(stale);
    }

    @Test
    @DisplayName("queries with a cutoff derived from the configured threshold")
    void cutoffFollowsTheConfiguredThreshold() {
        when(deviceRepository.findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any()))
                .thenReturn(List.of());
        LocalDateTime before = LocalDateTime.now().minusMinutes(5);

        scheduler.checkDeviceHeartbeats();

        ArgumentCaptor<LocalDateTime> cutoff = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(deviceRepository).findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), cutoff.capture());
        // Within a second of "now minus five minutes" either side of the call.
        assertThat(cutoff.getValue()).isBetween(before.minusSeconds(2), before.plusSeconds(2));
    }

    @Test
    @DisplayName("announces the transition so alerting can react")
    void publishesAnOfflineEvent() {
        Device stale = online("ESP32-001", LocalDateTime.now().minusMinutes(30));
        when(deviceRepository.findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any()))
                .thenReturn(List.of(stale));

        scheduler.checkDeviceHeartbeats();

        ArgumentCaptor<DeviceOfflineEvent> event = ArgumentCaptor.forClass(DeviceOfflineEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY_DEVICE_OFFLINE),
                event.capture());
        assertThat(event.getValue().getDeviceUid()).isEqualTo("ESP32-001");
        assertThat(event.getValue().getOfflineDetectedAt()).isNotNull();
    }

    @Test
    @DisplayName("a failed announcement does not abandon the remaining stations")
    void publishFailureDoesNotStopTheSweep() {
        Device first = online("ESP32-001", LocalDateTime.now().minusMinutes(30));
        Device second = online("ESP32-002", LocalDateTime.now().minusMinutes(30));
        when(deviceRepository.findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any()))
                .thenReturn(List.of(first, second));
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(DeviceOfflineEvent.class));

        // A broker outage must not leave half the fleet wrongly marked ONLINE.
        assertThatCode(scheduler::checkDeviceHeartbeats).doesNotThrowAnyException();

        assertThat(first.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
        assertThat(second.getStatus()).isEqualTo(DeviceStatus.OFFLINE);
        verify(deviceRepository).save(second);
    }

    @Test
    @DisplayName("does nothing when every station is reporting inside the threshold")
    void quietSweepChangesNothing() {
        when(deviceRepository.findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any()))
                .thenReturn(List.of());

        scheduler.checkDeviceHeartbeats();

        verify(deviceRepository, never()).save(any());
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(DeviceOfflineEvent.class));
    }

    @Test
    @DisplayName("only ONLINE stations are candidates, so a station is not announced twice")
    void onlyOnlineDevicesAreSwept() {
        when(deviceRepository.findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any()))
                .thenReturn(List.of());

        scheduler.checkDeviceHeartbeats();

        // Filtering on ONLINE in the query is what stops an already-offline
        // station from re-emitting DeviceOfflineEvent on every sweep.
        verify(deviceRepository).findByStatusAndLastSeenAtBefore(eq(DeviceStatus.ONLINE), any());
    }
}
