package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.dto.DeviceFirmwareUpdateResponse;
import com.weatherplatform.deviceservice.dto.FirmwareCreateRequest;
import com.weatherplatform.deviceservice.dto.FirmwareResponse;
import com.weatherplatform.deviceservice.dto.FirmwareUpdateRequest;
import com.weatherplatform.deviceservice.dto.FirmwareUpdateStatusRequest;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceFirmwareUpdate;
import com.weatherplatform.deviceservice.entity.Firmware;
import com.weatherplatform.deviceservice.entity.FirmwareUpdateStatus;
import com.weatherplatform.deviceservice.event.FirmwareUpdateCompletedEvent;
import com.weatherplatform.deviceservice.event.FirmwareUpdateRequestedEvent;
import com.weatherplatform.deviceservice.exception.DuplicateResourceException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceFirmwareUpdateRepository;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import com.weatherplatform.deviceservice.repository.FirmwareRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class FirmwareServiceImpl implements FirmwareService {

    private final FirmwareRepository firmwareRepository;
    private final DeviceFirmwareUpdateRepository updateRepository;
    private final DeviceRepository deviceRepository;
    private final RabbitTemplate rabbitTemplate;

    @Override
    @Transactional
    public FirmwareResponse createFirmware(FirmwareCreateRequest request) {
        log.info("Creating firmware version: {}", request.getVersion());
        if (firmwareRepository.existsByVersion(request.getVersion())) {
            throw new DuplicateResourceException("Firmware version '" + request.getVersion() + "' already exists");
        }

        Firmware firmware = Firmware.builder()
                .version(request.getVersion())
                .fileUrl(request.getFileUrl())
                .checksum(request.getChecksum())
                .fileSize(request.getFileSize())
                .releaseNotes(request.getReleaseNotes())
                .build();

        Firmware saved = firmwareRepository.save(firmware);
        log.info("Firmware {} created successfully with ID {}", saved.getVersion(), saved.getId());
        return mapToFirmwareResponse(saved);
    }

    @Override
    public List<FirmwareResponse> getAllFirmware() {
        return firmwareRepository.findAll().stream()
                .map(this::mapToFirmwareResponse)
                .toList();
    }

    @Override
    public FirmwareResponse getFirmwareByVersion(String version) {
        Firmware firmware = firmwareRepository.findByVersion(version)
                .orElseThrow(() -> new ResourceNotFoundException("Firmware not found for version: " + version));
        return mapToFirmwareResponse(firmware);
    }

    @Override
    @Transactional
    public DeviceFirmwareUpdateResponse requestUpdate(String deviceUid, FirmwareUpdateRequest request) {
        log.info("Requesting firmware update for device: {} to version: {}", deviceUid, request.getFirmwareVersion());
        Device device = deviceRepository.findByDeviceUid(deviceUid)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found with UID: " + deviceUid));

        Firmware firmware = firmwareRepository.findByVersion(request.getFirmwareVersion())
                .orElseThrow(() -> new ResourceNotFoundException("Firmware version not found: " + request.getFirmwareVersion()));

        DeviceFirmwareUpdate update = DeviceFirmwareUpdate.builder()
                .deviceUid(device.getDeviceUid())
                .firmwareVersion(firmware.getVersion())
                .status(FirmwareUpdateStatus.PENDING)
                .build();

        DeviceFirmwareUpdate saved = updateRepository.save(update);

        try {
            FirmwareUpdateRequestedEvent event = FirmwareUpdateRequestedEvent.builder()
                    .updateId(saved.getId())
                    .deviceUid(device.getDeviceUid())
                    .firmwareVersion(firmware.getVersion())
                    .fileUrl(firmware.getFileUrl())
                    .checksum(firmware.getChecksum())
                    .requestedAt(saved.getStartedAt())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY_FIRMWARE_REQUESTED, event);
        } catch (Exception e) {
            log.error("Failed to publish FirmwareUpdateRequestedEvent for update {}", saved.getId(), e);
        }

        return mapToUpdateResponse(saved);
    }

    @Override
    @Transactional
    public DeviceFirmwareUpdateResponse updateStatus(String deviceUid, Long updateId, FirmwareUpdateStatusRequest request) {
        log.info("Updating firmware update status for device {} update ID {} to {}", deviceUid, updateId, request.getStatus());
        DeviceFirmwareUpdate update = updateRepository.findById(updateId)
                .orElseThrow(() -> new ResourceNotFoundException("Firmware update record not found with ID: " + updateId));

        if (!update.getDeviceUid().equals(deviceUid)) {
            throw new ResourceNotFoundException("Update ID " + updateId + " does not belong to device " + deviceUid);
        }

        update.setStatus(request.getStatus());
        if (request.getErrorMessage() != null) {
            update.setErrorMessage(request.getErrorMessage());
        }

        if (request.getStatus() == FirmwareUpdateStatus.SUCCESS || request.getStatus() == FirmwareUpdateStatus.FAILED || request.getStatus() == FirmwareUpdateStatus.ROLLED_BACK) {
            update.setCompletedAt(LocalDateTime.now());
        }

        if (request.getStatus() == FirmwareUpdateStatus.SUCCESS) {
            deviceRepository.findByDeviceUid(deviceUid).ifPresent(device -> {
                device.setFirmwareVersion(update.getFirmwareVersion());
                deviceRepository.save(device);
                log.info("Device {} firmware updated to {}", deviceUid, update.getFirmwareVersion());
            });
        }

        DeviceFirmwareUpdate saved = updateRepository.save(update);

        if (saved.getCompletedAt() != null) {
            try {
                FirmwareUpdateCompletedEvent event = FirmwareUpdateCompletedEvent.builder()
                        .updateId(saved.getId())
                        .deviceUid(saved.getDeviceUid())
                        .firmwareVersion(saved.getFirmwareVersion())
                        .status(saved.getStatus())
                        .completedAt(saved.getCompletedAt())
                        .errorMessage(saved.getErrorMessage())
                        .build();
                rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.ROUTING_KEY_FIRMWARE_COMPLETED, event);
            } catch (Exception e) {
                log.error("Failed to publish FirmwareUpdateCompletedEvent for update {}", saved.getId(), e);
            }
        }

        return mapToUpdateResponse(saved);
    }

    @Override
    public List<DeviceFirmwareUpdateResponse> getFirmwareHistory(String deviceUid) {
        if (!deviceRepository.existsByDeviceUid(deviceUid)) {
            throw new ResourceNotFoundException("Device not found with UID: " + deviceUid);
        }
        return updateRepository.findByDeviceUidOrderByStartedAtDesc(deviceUid).stream()
                .map(this::mapToUpdateResponse)
                .toList();
    }

    private FirmwareResponse mapToFirmwareResponse(Firmware entity) {
        return FirmwareResponse.builder()
                .id(entity.getId())
                .version(entity.getVersion())
                .fileUrl(entity.getFileUrl())
                .checksum(entity.getChecksum())
                .fileSize(entity.getFileSize())
                .releaseNotes(entity.getReleaseNotes())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    private DeviceFirmwareUpdateResponse mapToUpdateResponse(DeviceFirmwareUpdate entity) {
        return DeviceFirmwareUpdateResponse.builder()
                .id(entity.getId())
                .deviceUid(entity.getDeviceUid())
                .firmwareVersion(entity.getFirmwareVersion())
                .status(entity.getStatus())
                .startedAt(entity.getStartedAt())
                .completedAt(entity.getCompletedAt())
                .errorMessage(entity.getErrorMessage())
                .build();
    }
}
