package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.config.RabbitMQConfig;
import com.weatherplatform.deviceservice.dto.DeviceFirmwareUpdateResponse;
import com.weatherplatform.deviceservice.dto.FirmwareCreateRequest;
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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Firmware is the most disruptive thing this platform can do to a station: an
 * interrupted install can take an instrument off the network until someone
 * drives out to it.
 *
 * So the invariants that matter are about not lying either way -- a request is
 * recorded as requested rather than installed, a device's recorded version only
 * moves on an actual success, and an update belonging to one station can never
 * be driven from another station's endpoint.
 */
@ExtendWith(MockitoExtension.class)
class FirmwareServiceImplTest {

    @Mock
    private FirmwareRepository firmwareRepository;
    @Mock
    private DeviceFirmwareUpdateRepository updateRepository;
    @Mock
    private DeviceRepository deviceRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;

    @InjectMocks
    private FirmwareServiceImpl service;

    private static Device device(String uid, String firmwareVersion) {
        Device device = new Device();
        device.setDeviceUid(uid);
        device.setFirmwareVersion(firmwareVersion);
        return device;
    }

    private static Firmware firmware(String version) {
        return Firmware.builder()
                .id(1L)
                .version(version)
                .fileUrl("https://example.invalid/fw/" + version + ".bin")
                .checksum("abc123")
                .fileSize(1024L)
                .build();
    }

    private static DeviceFirmwareUpdate pending(String uid, String version) {
        return DeviceFirmwareUpdate.builder()
                .id(7L)
                .deviceUid(uid)
                .firmwareVersion(version)
                .status(FirmwareUpdateStatus.PENDING)
                .startedAt(LocalDateTime.now())
                .build();
    }

    // -------------------------------------------------------- catalogue

    @Test
    @DisplayName("refuses to register a version that already exists")
    void duplicateVersionIsRejected() {
        when(firmwareRepository.existsByVersion("1.4.0")).thenReturn(true);

        // Two binaries under one version number would make "which firmware is
        // running" unanswerable for the whole fleet.
        assertThatThrownBy(() -> service.createFirmware(
                FirmwareCreateRequest.builder().version("1.4.0").build()))
                .isInstanceOf(DuplicateResourceException.class)
                .hasMessageContaining("1.4.0");

        verify(firmwareRepository, never()).save(any());
    }

    // ---------------------------------------------------------- request

    @Test
    @DisplayName("records a request as PENDING, never as installed")
    void requestIsRecordedAsPending() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device("ESP32-001", "1.3.0")));
        when(firmwareRepository.findByVersion("1.4.0")).thenReturn(Optional.of(firmware("1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeviceFirmwareUpdateResponse response = service.requestUpdate(
                "ESP32-001", FirmwareUpdateRequest.builder().firmwareVersion("1.4.0").build());

        // The station decides when it is safe to apply the update; until it
        // reports back, nothing has been installed.
        assertThat(response.getStatus()).isEqualTo(FirmwareUpdateStatus.PENDING);
        ArgumentCaptor<DeviceFirmwareUpdate> saved = ArgumentCaptor.forClass(DeviceFirmwareUpdate.class);
        verify(updateRepository).save(saved.capture());
        assertThat(saved.getValue().getStatus()).isEqualTo(FirmwareUpdateStatus.PENDING);
    }

    @Test
    @DisplayName("does not move the device's recorded version on a mere request")
    void requestDoesNotChangeTheDevicesVersion() {
        Device station = device("ESP32-001", "1.3.0");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(station));
        when(firmwareRepository.findByVersion("1.4.0")).thenReturn(Optional.of(firmware("1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.requestUpdate("ESP32-001", FirmwareUpdateRequest.builder().firmwareVersion("1.4.0").build());

        assertThat(station.getFirmwareVersion()).isEqualTo("1.3.0");
        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses an update for an unknown device or an unknown version")
    void requestValidatesBothEnds() {
        when(deviceRepository.findByDeviceUid("GHOST")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestUpdate(
                "GHOST", FirmwareUpdateRequest.builder().firmwareVersion("1.4.0").build()))
                .isInstanceOf(ResourceNotFoundException.class);

        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device("ESP32-001", "1.3.0")));
        when(firmwareRepository.findByVersion("9.9.9")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.requestUpdate(
                "ESP32-001", FirmwareUpdateRequest.builder().firmwareVersion("9.9.9").build()))
                .isInstanceOf(ResourceNotFoundException.class);

        verify(updateRepository, never()).save(any());
    }

    @Test
    @DisplayName("announces the request with everything the station needs to fetch and verify the image")
    void requestEventCarriesUrlAndChecksum() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device("ESP32-001", "1.3.0")));
        when(firmwareRepository.findByVersion("1.4.0")).thenReturn(Optional.of(firmware("1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.requestUpdate("ESP32-001", FirmwareUpdateRequest.builder().firmwareVersion("1.4.0").build());

        ArgumentCaptor<FirmwareUpdateRequestedEvent> event =
                ArgumentCaptor.forClass(FirmwareUpdateRequestedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY_FIRMWARE_REQUESTED),
                event.capture());
        // Without the checksum the station cannot tell a truncated download
        // from a good one before flashing it.
        assertThat(event.getValue().getChecksum()).isEqualTo("abc123");
        assertThat(event.getValue().getFileUrl()).contains("1.4.0");
    }

    @Test
    @DisplayName("a broker outage still leaves the request recorded")
    void requestSurvivesABrokerOutage() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device("ESP32-001", "1.3.0")));
        when(firmwareRepository.findByVersion("1.4.0")).thenReturn(Optional.of(firmware("1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        doThrow(new AmqpException("broker down"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(FirmwareUpdateRequestedEvent.class));

        assertThatCode(() -> service.requestUpdate(
                "ESP32-001", FirmwareUpdateRequest.builder().firmwareVersion("1.4.0").build()))
                .doesNotThrowAnyException();

        verify(updateRepository).save(any());
    }

    // ----------------------------------------------------- status moves

    @Test
    @DisplayName("moves the device's recorded version only on SUCCESS")
    void successUpdatesTheDeviceVersion() {
        Device station = device("ESP32-001", "1.3.0");
        when(updateRepository.findById(7L)).thenReturn(Optional.of(pending("ESP32-001", "1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(station));

        service.updateStatus("ESP32-001", 7L,
                FirmwareUpdateStatusRequest.builder().status(FirmwareUpdateStatus.SUCCESS).build());

        assertThat(station.getFirmwareVersion()).isEqualTo("1.4.0");
    }

    @Test
    @DisplayName("a failed install leaves the recorded version where it was")
    void failureDoesNotUpdateTheDeviceVersion() {
        Device station = device("ESP32-001", "1.3.0");
        when(updateRepository.findById(7L)).thenReturn(Optional.of(pending("ESP32-001", "1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateStatus("ESP32-001", 7L,
                FirmwareUpdateStatusRequest.builder()
                        .status(FirmwareUpdateStatus.FAILED)
                        .errorMessage("checksum mismatch")
                        .build());

        // Recording 1.4.0 here would tell operators a station is running an
        // image it rejected.
        assertThat(station.getFirmwareVersion()).isEqualTo("1.3.0");
        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("stamps a completion time and announces terminal states only")
    void terminalStatesAreCompletedAndAnnounced() {
        when(updateRepository.findById(7L)).thenReturn(Optional.of(pending("ESP32-001", "1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        DeviceFirmwareUpdateResponse response = service.updateStatus("ESP32-001", 7L,
                FirmwareUpdateStatusRequest.builder()
                        .status(FirmwareUpdateStatus.FAILED)
                        .errorMessage("checksum mismatch")
                        .build());

        assertThat(response.getCompletedAt()).isNotNull();
        ArgumentCaptor<FirmwareUpdateCompletedEvent> event =
                ArgumentCaptor.forClass(FirmwareUpdateCompletedEvent.class);
        verify(rabbitTemplate).convertAndSend(
                eq(RabbitMQConfig.EXCHANGE_NAME),
                eq(RabbitMQConfig.ROUTING_KEY_FIRMWARE_COMPLETED),
                event.capture());
        assertThat(event.getValue().getErrorMessage()).isEqualTo("checksum mismatch");
    }

    @Test
    @DisplayName("an in-flight status is not treated as a completion")
    void nonTerminalStatusIsNotAnnouncedAsComplete() {
        when(updateRepository.findById(7L)).thenReturn(Optional.of(pending("ESP32-001", "1.4.0")));
        when(updateRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.updateStatus("ESP32-001", 7L,
                FirmwareUpdateStatusRequest.builder().status(FirmwareUpdateStatus.PENDING).build());

        verify(rabbitTemplate, never()).convertAndSend(
                anyString(), anyString(), any(FirmwareUpdateCompletedEvent.class));
    }

    @Test
    @DisplayName("one station cannot drive another station's update record")
    void updateRecordIsScopedToItsOwnDevice() {
        when(updateRepository.findById(7L)).thenReturn(Optional.of(pending("ESP32-001", "1.4.0")));

        // Update 7 belongs to ESP32-001; reaching it through ESP32-002's path
        // would let one station report a firmware outcome for another.
        assertThatThrownBy(() -> service.updateStatus("ESP32-002", 7L,
                FirmwareUpdateStatusRequest.builder().status(FirmwareUpdateStatus.SUCCESS).build()))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("does not belong");

        verify(updateRepository, never()).save(any());
        verify(deviceRepository, never()).save(any());
    }

    @Test
    @DisplayName("refuses history for a device that does not exist")
    void historyRequiresAKnownDevice() {
        when(deviceRepository.existsByDeviceUid("GHOST")).thenReturn(false);

        assertThatThrownBy(() -> service.getFirmwareHistory("GHOST"))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
