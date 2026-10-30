package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.dto.DeviceHealthResponse;
import com.weatherplatform.deviceservice.dto.DeviceRegistrationRequest;
import com.weatherplatform.deviceservice.dto.DeviceResponse;
import com.weatherplatform.deviceservice.dto.DeviceStatusUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceUpdateRequest;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.exception.DuplicateResourceException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.mapper.DeviceMapper;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DeviceServiceImplTest {

    @Mock
    private DeviceRepository deviceRepository;

    @Mock
    private DeviceMapper deviceMapper;

    @InjectMocks
    private DeviceServiceImpl deviceService;

    private Device sampleDevice;
    private DeviceResponse sampleResponse;
    private DeviceRegistrationRequest registrationRequest;

    @BeforeEach
    void setUp() {
        // @Value is not injected by @InjectMocks, so this field would otherwise be
        // 0 — making "last seen just now" instantly stale and the health check a
        // coin flip on clock granularity.
        ReflectionTestUtils.setField(deviceService, "offlineThresholdMinutes", 5);

        sampleDevice = Device.builder()
                .id(1L)
                .deviceUid("ESP32-001")
                .deviceName("Weather Station Alpha")
                .locationName("Rooftop A")
                .latitude(23.8103)
                .longitude(90.4125)
                .status(DeviceStatus.INACTIVE)
                .registeredAt(LocalDateTime.now())
                .build();

        sampleResponse = DeviceResponse.builder()
                .id(1L)
                .deviceUid("ESP32-001")
                .deviceName("Weather Station Alpha")
                .locationName("Rooftop A")
                .latitude(23.8103)
                .longitude(90.4125)
                .status(DeviceStatus.INACTIVE)
                .registeredAt(LocalDateTime.now())
                .build();

        registrationRequest = DeviceRegistrationRequest.builder()
                .deviceUid("ESP32-001")
                .deviceName("Weather Station Alpha")
                .locationName("Rooftop A")
                .latitude(23.8103)
                .longitude(90.4125)
                .build();
    }

    @Test
    @DisplayName("registerDevice — success")
    void registerDevice_Success() {
        when(deviceRepository.existsByDeviceUid("ESP32-001")).thenReturn(false);
        when(deviceMapper.toEntity(registrationRequest)).thenReturn(sampleDevice);
        when(deviceRepository.save(sampleDevice)).thenReturn(sampleDevice);
        when(deviceMapper.toResponse(sampleDevice)).thenReturn(sampleResponse);

        DeviceResponse result = deviceService.registerDevice(registrationRequest);

        assertThat(result).isNotNull();
        assertThat(result.getDeviceUid()).isEqualTo("ESP32-001");
        verify(deviceRepository).save(any(Device.class));
    }

    @Test
    @DisplayName("registerDevice — duplicate throws DuplicateResourceException")
    void registerDevice_Duplicate() {
        when(deviceRepository.existsByDeviceUid("ESP32-001")).thenReturn(true);

        assertThatThrownBy(() -> deviceService.registerDevice(registrationRequest))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("ESP32-001");

        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("getDeviceByUid — found")
    void getDeviceByUid_Found() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(sampleDevice));
        when(deviceMapper.toResponse(sampleDevice)).thenReturn(sampleResponse);

        DeviceResponse result = deviceService.getDeviceByUid("ESP32-001");

        assertThat(result.getDeviceUid()).isEqualTo("ESP32-001");
    }

    @Test
    @DisplayName("getDeviceByUid — not found throws ResourceNotFoundException")
    void getDeviceByUid_NotFound() {
        when(deviceRepository.findByDeviceUid("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deviceService.getDeviceByUid("UNKNOWN"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    @DisplayName("updateDevice — success with partial update")
    void updateDevice_Success() {
        DeviceUpdateRequest updateReq = DeviceUpdateRequest.builder()
                .deviceName("Updated Name")
                .build();

        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(sampleDevice));
        when(deviceRepository.save(any(Device.class))).thenReturn(sampleDevice);
        when(deviceMapper.toResponse(any(Device.class))).thenReturn(sampleResponse);

        DeviceResponse result = deviceService.updateDevice("ESP32-001", updateReq);

        assertThat(result).isNotNull();
        verify(deviceRepository).save(any(Device.class));
    }

    @Test
    @DisplayName("deleteDevice — success")
    void deleteDevice_Success() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(sampleDevice));

        deviceService.deleteDevice("ESP32-001");

        verify(deviceRepository).delete(sampleDevice);
    }

    @Test
    @DisplayName("getDeviceHealth — healthy ONLINE device")
    void getDeviceHealth_Healthy() {
        sampleDevice.setStatus(DeviceStatus.ONLINE);
        sampleDevice.setLastSeenAt(LocalDateTime.now());
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(sampleDevice));

        DeviceHealthResponse health = deviceService.getDeviceHealth("ESP32-001");

        assertThat(health.isHealthy()).isTrue();
        assertThat(health.getMessage()).contains("online");
    }

    @Test
    @DisplayName("getDeviceHealth — unhealthy device never reported")
    void getDeviceHealth_NeverReported() {
        sampleDevice.setStatus(DeviceStatus.INACTIVE);
        sampleDevice.setLastSeenAt(null);
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(sampleDevice));

        DeviceHealthResponse health = deviceService.getDeviceHealth("ESP32-001");

        assertThat(health.isHealthy()).isFalse();
        assertThat(health.getMessage()).contains("never reported");
    }
}
