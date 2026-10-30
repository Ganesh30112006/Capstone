package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DigitalTwinResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceDigitalTwin;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.event.DeviceHeartbeatEvent;
import com.weatherplatform.deviceservice.event.SensorReadingEvent;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceDigitalTwinRepository;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The digital twin is the console's answer to "what is this station doing right
 * now", assembled from whatever telemetry and heartbeats have arrived.
 *
 * Two families of property are pinned here. The first is that the twin only
 * reports what a station has actually sent: a probe that has never reported is
 * absent from the response rather than present at zero, and an unknown firmware
 * or battery stays unknown. The second is that an update is a merge, not a
 * replacement -- a heartbeat carrying only a battery level must not erase the
 * firmware version learned from the previous one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigitalTwinServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private DeviceDigitalTwinRepository twinRepository;

    @InjectMocks
    private DigitalTwinServiceImpl twinService;

    private static final LocalDateTime SEEN = LocalDateTime.of(2026, 9, 1, 10, 0);

    private static Device station(String uid) {
        Device device = new Device();
        device.setDeviceUid(uid);
        device.setDeviceName("North Ridge");
        device.setLocationName("Ridge");
        device.setLatitude(17.385);
        device.setLongitude(78.4867);
        device.setStatus(DeviceStatus.ONLINE);
        return device;
    }

    private DeviceDigitalTwin savedTwin() {
        ArgumentCaptor<DeviceDigitalTwin> captor = ArgumentCaptor.forClass(DeviceDigitalTwin.class);
        verify(twinRepository).save(captor.capture());
        return captor.getValue();
    }

    @Nested
    @DisplayName("reading the twin")
    class Reading {

        @Test
        @DisplayName("refuses an unknown device rather than returning a blank twin")
        void unknownDeviceIsNotFound() {
            when(deviceRepository.findByDeviceUid("GHOST-1")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> twinService.getDigitalTwin("GHOST-1"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("GHOST-1");
        }

        @Test
        @DisplayName("a station that has never reported has no sensors, not sensors reading zero")
        void neverReportedStationHasNoSensorEntries() {
            when(deviceRepository.findByDeviceUid("ESP32-NEW")).thenReturn(Optional.of(station("ESP32-NEW")));
            when(twinRepository.findByDeviceUid("ESP32-NEW")).thenReturn(Optional.empty());

            DigitalTwinResponse twin = twinService.getDigitalTwin("ESP32-NEW");

            // An entry here would be a measurement the station never sent.
            assertThat(twin.getSensors()).isEmpty();
        }

        @Test
        @DisplayName("omits a probe that has no value, keeping the ones that do")
        void omitsProbesWithNoValue() {
            when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(station("ESP32-001")));
            DeviceDigitalTwin stored = new DeviceDigitalTwin();
            stored.setDeviceUid("ESP32-001");
            stored.setTemperature(BigDecimal.valueOf(21.5));
            stored.setTemperatureStatus("HEALTHY");
            // Humidity, pressure, oxygen, air quality and rain have never arrived.
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(stored));

            DigitalTwinResponse twin = twinService.getDigitalTwin("ESP32-001");

            assertThat(twin.getSensors()).containsOnlyKeys("temperature");
            assertThat(twin.getSensors().get("temperature").getValue()).isEqualByComparingTo("21.5");
        }

        @Test
        @DisplayName("an unknown firmware version says UNKNOWN rather than naming a version")
        void unknownFirmwareIsNotInvented() {
            Device device = station("ESP32-001");
            device.setFirmwareVersion(null);
            when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.empty());

            DigitalTwinResponse twin = twinService.getDigitalTwin("ESP32-001");

            assertThat(twin.getFirmware()).isEqualTo("UNKNOWN");
        }

        @Test
        @DisplayName("an unknown battery or signal stays null, never zero")
        void unknownHealthFiguresStayNull() {
            Device device = station("ESP32-001");
            device.setBatteryLevel(null);
            device.setWifiSignalStrength(null);
            when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.empty());

            DigitalTwinResponse twin = twinService.getDigitalTwin("ESP32-001");

            // Zero here reads as a flat battery and no signal -- two alarming
            // claims about a station that has simply not said.
            assertThat(twin.getHealth().getBattery()).isNull();
            assertThat(twin.getHealth().getWifiSignal()).isNull();
        }

        @Test
        @DisplayName("reports rain as detected or not, and carries each probe's status through")
        void reportsRainAndStatuses() {
            when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(station("ESP32-001")));
            DeviceDigitalTwin stored = new DeviceDigitalTwin();
            stored.setDeviceUid("ESP32-001");
            stored.setRain(Boolean.TRUE);
            stored.setRainStatus("HEALTHY");
            stored.setHumidity(BigDecimal.valueOf(61));
            stored.setHumidityStatus("WARNING");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(stored));

            DigitalTwinResponse twin = twinService.getDigitalTwin("ESP32-001");

            assertThat(twin.getSensors().get("rain").getValue()).isEqualByComparingTo(BigDecimal.ONE);
            assertThat(twin.getSensors().get("humidity").getStatus()).isEqualTo("WARNING");
        }

        @Test
        @DisplayName("carries the station's own identity and position, not the twin's")
        void reportsTheDeviceIdentity() {
            when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(station("ESP32-001")));
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.empty());

            DigitalTwinResponse twin = twinService.getDigitalTwin("ESP32-001");

            assertThat(twin.getDeviceUid()).isEqualTo("ESP32-001");
            assertThat(twin.getDeviceName()).isEqualTo("North Ridge");
            assertThat(twin.getStatus()).isEqualTo("ONLINE");
            assertThat(twin.getLocation().getLatitude()).isEqualTo(17.385);
        }
    }

    @Nested
    @DisplayName("telemetry updates")
    class Telemetry {

        @Test
        @DisplayName("records the reported values on the existing twin")
        void appliesReportedValues() {
            SensorReadingEvent event = new SensorReadingEvent();
            event.setDeviceUid("ESP32-001");
            event.setTemperature(BigDecimal.valueOf(26.5));
            event.setTemperatureStatus("HEALTHY");
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            twinService.updateSensorState(event);

            verify(twinRepository).save(any(DeviceDigitalTwin.class));
            assertThat(existing.getTemperature()).isEqualByComparingTo("26.5");
            assertThat(existing.getTemperatureStatus()).isEqualTo("HEALTHY");
        }

        @Test
        @DisplayName("creates a twin for a station reporting for the first time")
        void createsATwinOnFirstReading() {
            SensorReadingEvent event = new SensorReadingEvent();
            event.setDeviceUid("ESP32-NEW");
            event.setTemperature(BigDecimal.valueOf(19.0));
            when(twinRepository.findByDeviceUid("ESP32-NEW")).thenReturn(Optional.empty());

            twinService.updateSensorState(event);

            assertThat(savedTwin().getDeviceUid()).isEqualTo("ESP32-NEW");
        }

        @Test
        @DisplayName("a partial reading does not blank the values it does not mention")
        void partialReadingDoesNotEraseKnownValues() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            existing.setHumidity(BigDecimal.valueOf(61));
            existing.setPressure(BigDecimal.valueOf(1008.4));
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            SensorReadingEvent event = new SensorReadingEvent();
            event.setDeviceUid("ESP32-001");
            event.setTemperature(BigDecimal.valueOf(26.5));

            twinService.updateSensorState(event);

            // A reading that omits humidity says nothing about humidity; it does
            // not say the humidity sensor has stopped having a value.
            assertThat(existing.getHumidity()).isEqualByComparingTo("61");
            assertThat(existing.getPressure()).isEqualByComparingTo("1008.4");
        }

        @Test
        @DisplayName("keeps the previous status when a reading carries none")
        void keepsThePreviousStatusWhenNoneIsReported() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            existing.setTemperatureStatus("WARNING");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            SensorReadingEvent event = new SensorReadingEvent();
            event.setDeviceUid("ESP32-001");
            event.setTemperature(BigDecimal.valueOf(26.5));

            twinService.updateSensorState(event);

            // Clearing this to HEALTHY would turn a silent event into an all-clear.
            assertThat(existing.getTemperatureStatus()).isEqualTo("WARNING");
        }

        @Test
        @DisplayName("records position and timestamp only when the reading carried them")
        void onlyRecordsPositionWhenReported() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            existing.setLastLatitude(17.0);
            existing.setLastLongitude(78.0);
            existing.setLastSeenAt(SEEN);
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            SensorReadingEvent event = new SensorReadingEvent();
            event.setDeviceUid("ESP32-001");
            event.setTemperature(BigDecimal.valueOf(26.5));

            twinService.updateSensorState(event);

            assertThat(existing.getLastLatitude()).isEqualTo(17.0);
            assertThat(existing.getLastSeenAt()).isEqualTo(SEEN);
        }

        @Test
        @DisplayName("telemetry proves the station is reachable")
        void telemetryMarksTheStationConnected() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            SensorReadingEvent event = new SensorReadingEvent();
            event.setDeviceUid("ESP32-001");
            event.setTemperature(BigDecimal.valueOf(26.5));
            event.setRecordedAt(SEEN);

            twinService.updateSensorState(event);

            assertThat(existing.getConnectionStatus()).isEqualTo("CONNECTED");
            assertThat(existing.getLastSeenAt()).isEqualTo(SEEN);
        }
    }

    @Nested
    @DisplayName("heartbeat updates")
    class Heartbeat {

        @Test
        @DisplayName("records what the heartbeat reported")
        void appliesReportedHealth() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            twinService.updateHeartbeatState(DeviceHeartbeatEvent.builder()
                    .deviceUid("ESP32-001")
                    .batteryLevel(84)
                    .wifiSignalStrength(-58)
                    .firmwareVersion("1.4.2")
                    .timestamp(SEEN)
                    .build());

            assertThat(existing.getBatteryLevel()).isEqualTo(84);
            assertThat(existing.getWifiSignalStrength()).isEqualTo(-58);
            assertThat(existing.getFirmwareVersion()).isEqualTo("1.4.2");
            assertThat(existing.getLastSeenAt()).isEqualTo(SEEN);
            assertThat(existing.getConnectionStatus()).isEqualTo("CONNECTED");
        }

        @Test
        @DisplayName("a partial heartbeat does not blank what an earlier one established")
        void partialHeartbeatDoesNotEraseKnownHealth() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            existing.setBatteryLevel(84);
            existing.setWifiSignalStrength(-58);
            existing.setFirmwareVersion("1.4.2");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            // A heartbeat carrying only a timestamp.
            twinService.updateHeartbeatState(DeviceHeartbeatEvent.builder()
                    .deviceUid("ESP32-001")
                    .timestamp(SEEN)
                    .build());

            // Nulling these would make the console show an unknown battery for a
            // station that just told it everything was fine.
            assertThat(existing.getBatteryLevel()).isEqualTo(84);
            assertThat(existing.getWifiSignalStrength()).isEqualTo(-58);
            assertThat(existing.getFirmwareVersion()).isEqualTo("1.4.2");
        }

        @Test
        @DisplayName("ignores a blank firmware string rather than recording it as the version")
        void ignoresABlankFirmwareVersion() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            existing.setFirmwareVersion("1.4.2");
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            twinService.updateHeartbeatState(DeviceHeartbeatEvent.builder()
                    .deviceUid("ESP32-001")
                    .firmwareVersion("   ")
                    .timestamp(SEEN)
                    .build());

            assertThat(existing.getFirmwareVersion()).isEqualTo("1.4.2");
        }

        @Test
        @DisplayName("creates a twin for a station whose heartbeat arrives before any telemetry")
        void createsATwinOnFirstHeartbeat() {
            when(twinRepository.findByDeviceUid("ESP32-NEW")).thenReturn(Optional.empty());

            twinService.updateHeartbeatState(DeviceHeartbeatEvent.builder()
                    .deviceUid("ESP32-NEW")
                    .batteryLevel(99)
                    .timestamp(SEEN)
                    .build());

            DeviceDigitalTwin saved = savedTwin();
            assertThat(saved.getDeviceUid()).isEqualTo("ESP32-NEW");
            assertThat(saved.getBatteryLevel()).isEqualTo(99);
            // No telemetry has arrived, so no probe has a value yet.
            assertThat(saved.getTemperature()).isNull();
        }

        @Test
        @DisplayName("a heartbeat with no timestamp leaves the last-seen time alone")
        void heartbeatWithoutTimestampDoesNotTouchLastSeen() {
            DeviceDigitalTwin existing = new DeviceDigitalTwin();
            existing.setDeviceUid("ESP32-001");
            existing.setLastSeenAt(SEEN);
            when(twinRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(existing));

            twinService.updateHeartbeatState(DeviceHeartbeatEvent.builder()
                    .deviceUid("ESP32-001")
                    .batteryLevel(84)
                    .build());

            assertThat(existing.getLastSeenAt()).isEqualTo(SEEN);
        }
    }
}
