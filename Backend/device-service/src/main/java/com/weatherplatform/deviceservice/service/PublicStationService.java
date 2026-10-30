package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.PublicStationResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.ZoneOffset;
import java.util.List;

/**
 * Read-only projection of the fleet for unauthenticated visitors.
 *
 * Kept separate from {@link DeviceService} so that the mapping to the public
 * shape lives in exactly one place — a field can never leak into the public
 * portal by someone extending the admin DTO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicStationService {

    private final DeviceRepository deviceRepository;

    public List<PublicStationResponse> listPublicStations() {
        return deviceRepository.findByPublicVisibleTrue().stream()
                .map(this::toPublicResponse)
                .toList();
    }

    public PublicStationResponse getPublicStation(String deviceUid) {
        Device device = deviceRepository.findByDeviceUidAndPublicVisibleTrue(deviceUid)
                // A non-public station is reported as absent rather than forbidden, so the
                // public API cannot be used to probe which private stations exist.
                .orElseThrow(() -> new ResourceNotFoundException("No public station found: " + deviceUid));
        return toPublicResponse(device);
    }

    private PublicStationResponse toPublicResponse(Device device) {
        return PublicStationResponse.builder()
                .stationId(device.getDeviceUid())
                .name(device.getDeviceName())
                .location(device.getLocationName())
                .latitude(device.getLatitude())
                .longitude(device.getLongitude())
                .status(device.getStatus())
                .lastReportedAt(device.getLastSeenAt() == null
                        ? null
                        : device.getLastSeenAt().toInstant(ZoneOffset.UTC))
                .build();
    }
}
