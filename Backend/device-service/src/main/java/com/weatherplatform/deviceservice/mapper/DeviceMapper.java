package com.weatherplatform.deviceservice.mapper;

import com.weatherplatform.deviceservice.dto.DeviceRegistrationRequest;
import com.weatherplatform.deviceservice.dto.DeviceResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import org.springframework.stereotype.Component;

@Component
public class DeviceMapper {

    public Device toEntity(DeviceRegistrationRequest request) {
        return Device.builder()
                .deviceUid(request.getDeviceUid())
                .deviceName(request.getDeviceName())
                .locationName(request.getLocationName())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .firmwareVersion(request.getFirmwareVersion())
                .status(DeviceStatus.INACTIVE)
                .build();
    }

    public DeviceResponse toResponse(Device entity) {
        return DeviceResponse.builder()
                .id(entity.getId())
                .deviceUid(entity.getDeviceUid())
                .deviceName(entity.getDeviceName())
                .locationName(entity.getLocationName())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .status(entity.getStatus())
                .batteryLevel(entity.getBatteryLevel())
                .wifiSignalStrength(entity.getWifiSignalStrength())
                .firmwareVersion(entity.getFirmwareVersion())
                .lastSeenAt(entity.getLastSeenAt())
                .registeredAt(entity.getRegisteredAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }
}
