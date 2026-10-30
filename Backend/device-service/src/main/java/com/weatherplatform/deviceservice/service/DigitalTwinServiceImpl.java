package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DigitalTwinResponse;
import com.weatherplatform.deviceservice.dto.SensorStateDTO;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceDigitalTwin;
import com.weatherplatform.deviceservice.event.DeviceHeartbeatEvent;
import com.weatherplatform.deviceservice.event.SensorReadingEvent;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceDigitalTwinRepository;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DigitalTwinServiceImpl implements DigitalTwinService {

    private final DeviceRepository deviceRepository;
    private final DeviceDigitalTwinRepository digitalTwinRepository;

    @Override
    public DigitalTwinResponse getDigitalTwin(String deviceUid) {
        log.debug("Building digital twin for device: {}", deviceUid);
        Device device = deviceRepository.findByDeviceUid(deviceUid)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found for UID: " + deviceUid));

        DeviceDigitalTwin twin = digitalTwinRepository.findByDeviceUid(deviceUid)
                .orElse(DeviceDigitalTwin.builder()
                        .deviceUid(deviceUid)
                        .temperatureStatus("UNKNOWN")
                        .humidityStatus("UNKNOWN")
                        .airQualityStatus("UNKNOWN")
                        .pressureStatus("UNKNOWN")
                        .rainStatus("UNKNOWN")
                        .oxygenStatus("UNKNOWN")
                        .build());

        Map<String, SensorStateDTO> sensors = new LinkedHashMap<>();

        if (twin.getTemperature() != null) {
            sensors.put("temperature", SensorStateDTO.builder()
                    .value(twin.getTemperature())
                    .status(twin.getTemperatureStatus() != null ? twin.getTemperatureStatus() : "UNKNOWN")
                    .build());
        }
        if (twin.getHumidity() != null) {
            sensors.put("humidity", SensorStateDTO.builder()
                    .value(twin.getHumidity())
                    .status(twin.getHumidityStatus() != null ? twin.getHumidityStatus() : "UNKNOWN")
                    .build());
        }
        if (twin.getAirQuality() != null) {
            sensors.put("airQuality", SensorStateDTO.builder()
                    .value(BigDecimal.valueOf(twin.getAirQuality()))
                    .status(twin.getAirQualityStatus() != null ? twin.getAirQualityStatus() : "UNKNOWN")
                    .build());
        }
        if (twin.getPressure() != null) {
            sensors.put("pressure", SensorStateDTO.builder()
                    .value(twin.getPressure())
                    .status(twin.getPressureStatus() != null ? twin.getPressureStatus() : "UNKNOWN")
                    .build());
        }
        if (twin.getRain() != null) {
            sensors.put("rain", SensorStateDTO.builder()
                    .value(twin.getRain() ? BigDecimal.ONE : BigDecimal.ZERO)
                    .status(twin.getRainStatus() != null ? twin.getRainStatus() : "UNKNOWN")
                    .build());
        }
        if (twin.getOxygen() != null) {
            sensors.put("oxygen", SensorStateDTO.builder()
                    .value(twin.getOxygen())
                    .status(twin.getOxygenStatus() != null ? twin.getOxygenStatus() : "UNKNOWN")
                    .build());
        }

        return DigitalTwinResponse.builder()
                .deviceUid(device.getDeviceUid())
                .deviceName(device.getDeviceName())
                .location(DigitalTwinResponse.LocationInfo.builder()
                        .latitude(device.getLatitude())
                        .longitude(device.getLongitude())
                        .name(device.getLocationName())
                        .build())
                .status(device.getStatus().name())
                .firmware(device.getFirmwareVersion() != null ? device.getFirmwareVersion() : "UNKNOWN")
                .health(DigitalTwinResponse.HealthInfo.builder()
                        .battery(device.getBatteryLevel())
                        .wifiSignal(device.getWifiSignalStrength())
                        .build())
                .sensors(sensors)
                .build();
    }

    @Override
    @Transactional
    public void updateSensorState(SensorReadingEvent event) {
        log.debug("Updating digital twin for device: {}", event.getDeviceUid());
        DeviceDigitalTwin twin = digitalTwinRepository.findByDeviceUid(event.getDeviceUid())
                .orElse(DeviceDigitalTwin.builder()
                        .deviceUid(event.getDeviceUid())
                        .build());

        if (event.getTemperature() != null) {
            twin.setTemperature(event.getTemperature());
            if (event.getTemperatureStatus() != null) {
                twin.setTemperatureStatus(event.getTemperatureStatus());
            }
        }
        if (event.getHumidity() != null) {
            twin.setHumidity(event.getHumidity());
            if (event.getHumidityStatus() != null) {
                twin.setHumidityStatus(event.getHumidityStatus());
            }
        }
        if (event.getAirQuality() != null) {
            twin.setAirQuality(event.getAirQuality());
            if (event.getAirQualityStatus() != null) {
                twin.setAirQualityStatus(event.getAirQualityStatus());
            }
        }
        if (event.getPressure() != null) {
            twin.setPressure(event.getPressure());
            if (event.getPressureStatus() != null) {
                twin.setPressureStatus(event.getPressureStatus());
            }
        }
        if (event.getRain() != null) {
            twin.setRain(event.getRain());
            if (event.getRainStatus() != null) {
                twin.setRainStatus(event.getRainStatus());
            }
        }
        if (event.getOxygen() != null) {
            twin.setOxygen(event.getOxygen());
            if (event.getOxygenStatus() != null) {
                twin.setOxygenStatus(event.getOxygenStatus());
            }
        }

        // Telemetry also proves the station is reachable and reports its position.
        if (event.getLatitude() != null && event.getLongitude() != null) {
            twin.setLastLatitude(event.getLatitude());
            twin.setLastLongitude(event.getLongitude());
        }
        if (event.getRecordedAt() != null) {
            twin.setLastSeenAt(event.getRecordedAt());
        }
        twin.setConnectionStatus("CONNECTED");

        digitalTwinRepository.save(twin);
    }

    @Override
    @Transactional
    public void updateHeartbeatState(DeviceHeartbeatEvent event) {
        log.debug("Updating digital twin heartbeat state for device: {}", event.getDeviceUid());

        DeviceDigitalTwin twin = digitalTwinRepository.findByDeviceUid(event.getDeviceUid())
                .orElse(DeviceDigitalTwin.builder()
                        .deviceUid(event.getDeviceUid())
                        .build());

        // Only overwrite what the heartbeat actually reported; a partial heartbeat
        // must not blank out values learned from an earlier one.
        if (event.getBatteryLevel() != null) {
            twin.setBatteryLevel(event.getBatteryLevel());
        }
        if (event.getWifiSignalStrength() != null) {
            twin.setWifiSignalStrength(event.getWifiSignalStrength());
        }
        if (event.getFirmwareVersion() != null && !event.getFirmwareVersion().isBlank()) {
            twin.setFirmwareVersion(event.getFirmwareVersion());
        }
        if (event.getTimestamp() != null) {
            twin.setLastSeenAt(event.getTimestamp());
        }
        twin.setConnectionStatus("CONNECTED");

        digitalTwinRepository.save(twin);
    }
}
