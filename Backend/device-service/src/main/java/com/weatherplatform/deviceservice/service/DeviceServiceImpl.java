package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.dto.DeviceHealthResponse;
import com.weatherplatform.deviceservice.dto.DeviceHeartbeatRequest;
import com.weatherplatform.deviceservice.dto.DeviceLocationResponse;
import com.weatherplatform.deviceservice.dto.DeviceLocationUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceRegistrationRequest;
import com.weatherplatform.deviceservice.dto.DeviceResponse;
import com.weatherplatform.deviceservice.dto.DeviceStatusUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceUpdateRequest;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.event.DeviceHeartbeatEvent;
import com.weatherplatform.deviceservice.event.DeviceRegisteredEvent;
import com.weatherplatform.deviceservice.exception.DuplicateResourceException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.mapper.DeviceMapper;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DeviceServiceImpl implements DeviceService {

    private final DeviceRepository deviceRepository;
    private final DeviceMapper deviceMapper;
    private final RabbitTemplate rabbitTemplate;

    @Value("${device.heartbeat.offline-threshold-minutes:5}")
    private int offlineThresholdMinutes;

    @Override
    @Transactional
    public DeviceResponse registerDevice(DeviceRegistrationRequest request) {
        log.info("Registering new device with UID: {}", request.getDeviceUid());
        if (deviceRepository.existsByDeviceUid(request.getDeviceUid())) {
            throw new DuplicateResourceException("Device with UID '" + request.getDeviceUid() + "' is already registered");
        }
        Device saved = deviceRepository.save(deviceMapper.toEntity(request));
        log.info("Device registered successfully with ID: {}", saved.getId());

        try {
            DeviceRegisteredEvent event = DeviceRegisteredEvent.builder()
                    .deviceUid(saved.getDeviceUid())
                    .deviceName(saved.getDeviceName())
                    .locationName(saved.getLocationName())
                    .latitude(saved.getLatitude())
                    .longitude(saved.getLongitude())
                    .registeredAt(saved.getRegisteredAt())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY_DEVICE_REGISTERED, event);
        } catch (Exception e) {
            log.error("Failed to publish DeviceRegisteredEvent for {}", saved.getDeviceUid(), e);
        }

        return deviceMapper.toResponse(saved);
    }

    @Override
    public List<DeviceResponse> getAllDevices() {
        log.debug("Fetching all devices");
        return deviceRepository.findAll().stream()
                .map(deviceMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    public DeviceResponse getDeviceByUid(String deviceUid) {
        log.debug("Fetching device with UID: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);
        return deviceMapper.toResponse(device);
    }

    @Override
    @Transactional
    public DeviceResponse updateDevice(String deviceUid, DeviceUpdateRequest request) {
        log.info("Updating device with UID: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);

        if (request.getDeviceName() != null) {
            device.setDeviceName(request.getDeviceName());
        }
        if (request.getLocationName() != null) {
            device.setLocationName(request.getLocationName());
        }
        if (request.getLatitude() != null) {
            device.setLatitude(request.getLatitude());
        }
        if (request.getLongitude() != null) {
            device.setLongitude(request.getLongitude());
        }

        Device updated = deviceRepository.save(device);
        log.info("Device {} updated successfully", deviceUid);
        return deviceMapper.toResponse(updated);
    }

    @Override
    @Transactional
    public void deleteDevice(String deviceUid) {
        log.info("Deleting device with UID: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);
        deviceRepository.delete(device);
        log.info("Device {} deleted successfully", deviceUid);
    }

    @Override
    @Transactional
    public DeviceResponse updateStatus(String deviceUid, DeviceStatusUpdateRequest request) {
        log.info("Updating status of device {} to {}", deviceUid, request.getStatus());
        Device device = findDeviceOrThrow(deviceUid);
        device.setStatus(request.getStatus());
        if (request.getStatus() == DeviceStatus.ONLINE) {
            device.setLastSeenAt(LocalDateTime.now());
        }
        Device updated = deviceRepository.save(device);
        return deviceMapper.toResponse(updated);
    }

    @Override
    public DeviceHealthResponse getDeviceHealth(String deviceUid) {
        log.debug("Checking health for device: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);

        boolean healthy = device.getStatus() == DeviceStatus.ONLINE;
        String message;

        if (device.getLastSeenAt() == null) {
            healthy = false;
            message = "Device has never reported telemetry";
        } else if (device.getLastSeenAt().isBefore(LocalDateTime.now().minusMinutes(offlineThresholdMinutes))) {
            healthy = false;
            message = "Device has not reported in over " + offlineThresholdMinutes + " minutes";
        } else if (device.getStatus() == DeviceStatus.MAINTENANCE) {
            healthy = false;
            message = "Device is under maintenance";
        } else if (device.getStatus() == DeviceStatus.OFFLINE) {
            message = "Device is offline";
        } else if (device.getStatus() == DeviceStatus.INACTIVE) {
            message = "Device is inactive and not yet sending data";
        } else {
            message = "Device is online and reporting normally";
        }

        return DeviceHealthResponse.builder()
                .deviceUid(device.getDeviceUid())
                .status(device.getStatus())
                .batteryLevel(device.getBatteryLevel())
                .wifiSignalStrength(device.getWifiSignalStrength())
                .firmwareVersion(device.getFirmwareVersion())
                .lastSeenAt(device.getLastSeenAt())
                .healthy(healthy)
                .message(message)
                .build();
    }

    @Override
    public List<DeviceResponse> getDevicesByStatus(DeviceStatus status) {
        log.debug("Fetching devices with status: {}", status);
        return deviceRepository.findByStatus(status).stream()
                .map(deviceMapper::toResponse)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void recordHeartbeat(String deviceUid) {
        log.debug("Recording heartbeat for device: {}", deviceUid);
        deviceRepository.findByDeviceUid(deviceUid).ifPresent(device -> {
            device.setLastSeenAt(LocalDateTime.now());
            if (device.getStatus() == DeviceStatus.OFFLINE || device.getStatus() == DeviceStatus.INACTIVE) {
                device.setStatus(DeviceStatus.ONLINE);
                log.info("Device {} status changed to ONLINE via heartbeat", deviceUid);
            }
            deviceRepository.save(device);
        });
    }

    @Override
    @Transactional
    public DeviceHealthResponse processHeartbeat(String deviceUid, DeviceHeartbeatRequest request) {
        DeviceHealthResponse health = applyHeartbeat(deviceUid, request);
        announceHeartbeat(deviceUid, request);
        return health;
    }

    @Override
    @Transactional
    public DeviceHealthResponse applyHeartbeat(String deviceUid, DeviceHeartbeatRequest request) {
        log.debug("Applying heartbeat for device: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);

        LocalDateTime now = request.getTimestamp() != null ? request.getTimestamp() : LocalDateTime.now();
        device.setLastSeenAt(now);

        if (request.getBatteryLevel() != null) {
            device.setBatteryLevel(request.getBatteryLevel());
        }
        if (request.getWifiSignalStrength() != null) {
            device.setWifiSignalStrength(request.getWifiSignalStrength());
        }
        if (request.getFirmwareVersion() != null && !request.getFirmwareVersion().isBlank()) {
            device.setFirmwareVersion(request.getFirmwareVersion());
        }

        if (device.getStatus() != DeviceStatus.MAINTENANCE) {
            device.setStatus(DeviceStatus.ONLINE);
        }

        deviceRepository.save(device);

        return getDeviceHealth(deviceUid);
    }

    /**
     * Announces a heartbeat on the bus. Only ever called for heartbeats that did
     * not come from the bus -- see {@link DeviceService#applyHeartbeat}.
     */
    private void announceHeartbeat(String deviceUid, DeviceHeartbeatRequest request) {
        Device device = findDeviceOrThrow(deviceUid);
        try {
            DeviceHeartbeatEvent event = DeviceHeartbeatEvent.builder()
                    .deviceUid(deviceUid)
                    .batteryLevel(device.getBatteryLevel())
                    .wifiSignalStrength(device.getWifiSignalStrength())
                    .firmwareVersion(device.getFirmwareVersion())
                    .timestamp(request.getTimestamp() != null ? request.getTimestamp() : LocalDateTime.now())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY_DEVICE_HEARTBEAT, event);
        } catch (Exception e) {
            log.error("Failed to publish DeviceHeartbeatEvent for {}", deviceUid, e);
        }
    }

    @Override
    public DeviceLocationResponse getLocation(String deviceUid) {
        log.debug("Fetching location for device: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);
        return DeviceLocationResponse.builder()
                .deviceUid(device.getDeviceUid())
                .locationName(device.getLocationName())
                .latitude(device.getLatitude())
                .longitude(device.getLongitude())
                .updatedAt(device.getUpdatedAt())
                .build();
    }

    @Override
    @Transactional
    public DeviceLocationResponse updateLocation(String deviceUid, DeviceLocationUpdateRequest request) {
        log.info("Updating location for device: {}", deviceUid);
        Device device = findDeviceOrThrow(deviceUid);

        if (request.getLocationName() != null && !request.getLocationName().isBlank()) {
            device.setLocationName(request.getLocationName());
        }
        device.setLatitude(request.getLatitude());
        device.setLongitude(request.getLongitude());

        Device updated = deviceRepository.save(device);
        log.info("Location updated for device {}", deviceUid);

        return DeviceLocationResponse.builder()
                .deviceUid(updated.getDeviceUid())
                .locationName(updated.getLocationName())
                .latitude(updated.getLatitude())
                .longitude(updated.getLongitude())
                .updatedAt(updated.getUpdatedAt())
                .build();
    }

    private Device findDeviceOrThrow(String deviceUid) {
        return deviceRepository.findByDeviceUid(deviceUid)
                .orElseThrow(() -> {
                    log.warn("Device not found for UID: {}", deviceUid);
                    return new ResourceNotFoundException("Device not found for UID: " + deviceUid);
                });
    }
}
