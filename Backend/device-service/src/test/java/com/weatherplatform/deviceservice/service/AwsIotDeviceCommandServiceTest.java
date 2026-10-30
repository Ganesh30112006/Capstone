package com.weatherplatform.deviceservice.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.deviceservice.config.IotProperties;
import com.weatherplatform.deviceservice.dto.DeviceCommandResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceCommand;
import com.weatherplatform.deviceservice.exception.BusinessException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.PublishRequest;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * device-service dispatches physical commands to field hardware, and until now
 * carried the platform's thinnest test coverage for its size.
 *
 * The properties worth pinning are the ones an operator's safety rests on: a
 * command only ever reaches the addressed station's own topic, an unprovisioned
 * station is refused rather than silently published to, and a broker failure is
 * reported as a failure instead of being dressed up as a delivery.
 */
@ExtendWith(MockitoExtension.class)
class AwsIotDeviceCommandServiceTest {

    @Mock
    private IotDataPlaneClient iotDataPlaneClient;
    @Mock
    private DeviceRepository deviceRepository;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MeterRegistry meterRegistry = new SimpleMeterRegistry();
    private IotProperties iotProperties;
    private AwsIotDeviceCommandService service;

    @BeforeEach
    void setUp() {
        iotProperties = new IotProperties();
        iotProperties.setTopicPrefix("weatherplatform/devices");
        service = new AwsIotDeviceCommandService(
                iotDataPlaneClient, deviceRepository, iotProperties, objectMapper, meterRegistry);
    }

    private static Device provisioned(String uid) {
        Device device = new Device();
        device.setDeviceUid(uid);
        device.setCertificateId("cert-" + uid);
        return device;
    }

    private PublishRequest capturePublish() {
        ArgumentCaptor<PublishRequest> captor = ArgumentCaptor.forClass(PublishRequest.class);
        verify(iotDataPlaneClient).publish(captor.capture());
        return captor.getValue();
    }

    // ------------------------------------------------------- happy path

    @ParameterizedTest
    @EnumSource(DeviceCommand.class)
    @DisplayName("publishes every supported command to the station's own OTA topic")
    void publishesEachSupportedCommand(DeviceCommand command) {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        DeviceCommandResponse response = service.send("ESP32-001", command, "admin@example.com");

        assertThat(response.getStatus()).isEqualTo("PUBLISHED");
        assertThat(response.getCommand()).isEqualTo(command);
        assertThat(response.getTopic()).isEqualTo("weatherplatform/devices/ESP32-001/ota");
        // The response deliberately says PUBLISHED, not EXECUTED: the broker
        // accepted it, the station has not necessarily acted on it.
        assertThat(response.getCommandId()).isNotBlank();
    }

    @Test
    @DisplayName("addresses only the named station's topic, never a neighbour's")
    void topicIsDerivedFromTheDeviceUid() {
        when(deviceRepository.findByDeviceUid("ESP32-042")).thenReturn(Optional.of(provisioned("ESP32-042")));

        service.send("ESP32-042", DeviceCommand.PING, "admin@example.com");

        assertThat(capturePublish().topic()).isEqualTo("weatherplatform/devices/ESP32-042/ota");
    }

    @Test
    @DisplayName("publishes at QoS 1 so a command is not silently dropped")
    void publishesAtLeastOnce() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        service.send("ESP32-001", DeviceCommand.REBOOT, "admin@example.com");

        // At-least-once, with the device deduplicating on commandId, so a
        // redelivered REBOOT does not reboot the station twice.
        assertThat(capturePublish().qos()).isEqualTo(1);
    }

    @Test
    @DisplayName("carries a unique command id and the issuing identity, but no credentials")
    void payloadCarriesIdentityNotSecrets() throws Exception {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        DeviceCommandResponse response = service.send("ESP32-001", DeviceCommand.OTA_CHECK, "admin@example.com");

        JsonNode payload = objectMapper.readTree(capturePublish().payload().asUtf8String());
        assertThat(payload.get("commandId").asText()).isEqualTo(response.getCommandId());
        assertThat(UUID.fromString(payload.get("commandId").asText())).isNotNull();
        assertThat(payload.get("command").asText()).isEqualTo("OTA_CHECK");
        assertThat(payload.get("issuedBy").asText()).isEqualTo("admin@example.com");
        // Identity only: a token or certificate in a device-bound payload would
        // put a platform credential on the wire.
        assertThat(payload.has("token")).isFalse();
        assertThat(payload.has("password")).isFalse();
        assertThat(payload.has("certificateId")).isFalse();
    }

    @Test
    @DisplayName("gives each dispatch its own command id")
    void commandIdsAreUnique() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        String first = service.send("ESP32-001", DeviceCommand.PING, "a@example.com").getCommandId();
        String second = service.send("ESP32-001", DeviceCommand.PING, "a@example.com").getCommandId();

        // Shared ids would make the device's deduplication drop the second one.
        assertThat(first).isNotEqualTo(second);
    }

    // ------------------------------------------------------- refusals

    @Test
    @DisplayName("refuses an unknown device instead of publishing to a derived topic")
    void unknownDeviceIsRefused() {
        when(deviceRepository.findByDeviceUid("GHOST-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.send("GHOST-1", DeviceCommand.PING, "admin@example.com"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("GHOST-1");

        // The topic is built from the UID, so an unchecked UID would publish to
        // a well-formed topic that nothing is subscribed to.
        verify(iotDataPlaneClient, never()).publish(any(PublishRequest.class));
    }

    @Test
    @DisplayName("refuses an unprovisioned station rather than publishing into the void")
    void unprovisionedDeviceIsRefused() {
        Device unprovisioned = new Device();
        unprovisioned.setDeviceUid("ESP32-NEW");
        unprovisioned.setCertificateId(null);
        when(deviceRepository.findByDeviceUid("ESP32-NEW")).thenReturn(Optional.of(unprovisioned));

        // A station with no IoT identity is subscribed to nothing: the publish
        // would succeed and the console would report a delivery that cannot
        // have happened.
        assertThatThrownBy(() -> service.send("ESP32-NEW", DeviceCommand.REBOOT, "admin@example.com"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Provision it");

        verify(iotDataPlaneClient, never()).publish(any(PublishRequest.class));
    }

    // --------------------------------------------------- broker failure

    @Test
    @DisplayName("a broker failure surfaces as a failure, not as a published command")
    void brokerFailurePropagates() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));
        when(iotDataPlaneClient.publish(any(PublishRequest.class)))
                .thenThrow(new RuntimeException("broker unreachable"));

        // Reporting PUBLISHED here would tell an operator their reboot landed.
        assertThatThrownBy(() -> service.send("ESP32-001", DeviceCommand.REBOOT, "admin@example.com"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("counts published commands for the dashboards")
    void recordsAMetricPerPublishedCommand() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        service.send("ESP32-001", DeviceCommand.PING, "admin@example.com");

        assertThat(meterRegistry.counter("device.commands.published", "command", "PING").count())
                .isEqualTo(1.0);
    }
}
