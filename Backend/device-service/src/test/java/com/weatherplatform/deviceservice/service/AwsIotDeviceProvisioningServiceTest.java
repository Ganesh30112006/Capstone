package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.config.IotProperties;
import com.weatherplatform.deviceservice.dto.DeviceProvisioningResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.exception.BusinessException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.services.iot.IotClient;
import software.amazon.awssdk.services.iot.model.AttachPolicyRequest;
import software.amazon.awssdk.services.iot.model.AttachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateRequest;
import software.amazon.awssdk.services.iot.model.CreateKeysAndCertificateResponse;
import software.amazon.awssdk.services.iot.model.CreateThingRequest;
import software.amazon.awssdk.services.iot.model.DeleteCertificateRequest;
import software.amazon.awssdk.services.iot.model.DetachPolicyRequest;
import software.amazon.awssdk.services.iot.model.DetachThingPrincipalRequest;
import software.amazon.awssdk.services.iot.model.IotException;
import software.amazon.awssdk.services.iot.model.KeyPair;
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Provisioning is where a station acquires the only credential that lets it
 * publish, so the failure modes here are security findings rather than bugs.
 *
 * Three properties carry the weight. A certificate is never issued twice for a
 * station that still holds a live one, because the old certificate would keep
 * working. A failure part-way through never leaves an active certificate in the
 * AWS account that no Thing references. And revocation deactivates before it
 * detaches, so the station loses the ability to connect at the first step
 * rather than the last.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AwsIotDeviceProvisioningServiceTest {

    @Mock
    private IotClient iotClient;
    @Mock
    private DeviceRepository deviceRepository;

    private IotProperties iotProperties;
    private AwsIotDeviceProvisioningService service;

    @BeforeEach
    void setUp() {
        iotProperties = new IotProperties();
        iotProperties.setMqttEndpoint("a1b2c3-ats.iot.eu-west-1.amazonaws.com");
        iotProperties.setPolicyName("weather-station-device-policy");
        iotProperties.setThingNamePrefix("weather-station-");
        iotProperties.setTopicPrefix("weatherplatform/devices");
        service = new AwsIotDeviceProvisioningService(iotClient, deviceRepository, iotProperties);
    }

    private static Device unprovisioned(String uid) {
        Device device = new Device();
        device.setDeviceUid(uid);
        return device;
    }

    private static Device provisioned(String uid) {
        Device device = unprovisioned(uid);
        device.setThingName("weather-station-" + uid);
        device.setCertificateId("cert-123");
        device.setCertificateArn("arn:aws:iot:eu-west-1:1:cert/cert-123");
        device.setIotConnectionStatus("PROVISIONED");
        return device;
    }

    private void awsIssuesACertificate() {
        when(iotClient.createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class)))
                .thenReturn(CreateKeysAndCertificateResponse.builder()
                        .certificateId("cert-123")
                        .certificateArn("arn:aws:iot:eu-west-1:1:cert/cert-123")
                        .certificatePem("-----BEGIN CERTIFICATE-----")
                        .keyPair(KeyPair.builder()
                                .privateKey("-----BEGIN RSA PRIVATE KEY-----")
                                .publicKey("-----BEGIN PUBLIC KEY-----")
                                .build())
                        .build());
    }

    private static IotException awsRefuses(String message) {
        return (IotException) IotException.builder()
                .awsErrorDetails(AwsErrorDetails.builder().errorMessage(message).build())
                .build();
    }

    // ------------------------------------------------------ happy path

    @Test
    @DisplayName("creates the Thing, certificate and policy attachment, then records them")
    void provisionsAndPersistsTheIdentity() {
        Device device = unprovisioned("ESP32-001");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
        awsIssuesACertificate();

        DeviceProvisioningResponse response = service.provision("ESP32-001");

        verify(iotClient).createThing(any(CreateThingRequest.class));
        verify(iotClient).attachThingPrincipal(any(AttachThingPrincipalRequest.class));
        verify(iotClient).attachPolicy(any(AttachPolicyRequest.class));
        verify(deviceRepository).save(device);

        assertThat(device.getThingName()).isEqualTo("weather-station-ESP32-001");
        assertThat(device.getCertificateId()).isEqualTo("cert-123");
        assertThat(device.getIotConnectionStatus()).isEqualTo("PROVISIONED");
        assertThat(response.getDeviceUid()).isEqualTo("ESP32-001");
        assertThat(response.getProvisionedAt()).isNotNull();
    }

    @Test
    @DisplayName("attaches the least-privilege policy by name, not an ad-hoc one")
    void attachesTheConfiguredPolicy() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(unprovisioned("ESP32-001")));
        awsIssuesACertificate();

        service.provision("ESP32-001");

        ArgumentCaptor<AttachPolicyRequest> policy = ArgumentCaptor.forClass(AttachPolicyRequest.class);
        verify(iotClient).attachPolicy(policy.capture());
        assertThat(policy.getValue().policyName()).isEqualTo("weather-station-device-policy");
        // The policy is scoped to the certificate, which is what confines the
        // station to its own topics.
        assertThat(policy.getValue().target()).isEqualTo("arn:aws:iot:eu-west-1:1:cert/cert-123");
    }

    @Test
    @DisplayName("hands back the station's own topics so the firmware cannot guess wrong")
    void returnsTheCanonicalTopicsForThisStation() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(unprovisioned("ESP32-001")));
        awsIssuesACertificate();

        DeviceProvisioningResponse response = service.provision("ESP32-001");

        assertThat(response.getTelemetryTopic()).isEqualTo("weatherplatform/devices/ESP32-001/telemetry");
        assertThat(response.getHeartbeatTopic()).isEqualTo("weatherplatform/devices/ESP32-001/heartbeat");
        assertThat(response.getOtaTopic()).isEqualTo("weatherplatform/devices/ESP32-001/ota");
        assertThat(response.getMqttEndpoint()).isEqualTo("a1b2c3-ats.iot.eu-west-1.amazonaws.com");
    }

    @Test
    @DisplayName("returns the private key once and never writes it to the device record")
    void privateKeyIsReturnedButNotStored() {
        Device device = unprovisioned("ESP32-001");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
        awsIssuesACertificate();

        DeviceProvisioningResponse response = service.provision("ESP32-001");

        assertThat(response.getPrivateKey()).startsWith("-----BEGIN RSA PRIVATE KEY-----");
        // AWS generates the key pair and this response is the only place the
        // private half ever exists. Anything persisted here would turn the
        // device table into a store of usable device credentials.
        assertThat(device.getCertificateArn()).doesNotContain("PRIVATE KEY");
        assertThat(device.getCertificateId()).doesNotContain("PRIVATE KEY");
        assertThat(device.getIotConnectionStatus()).doesNotContain("PRIVATE KEY");
    }

    @Test
    @DisplayName("reuses an existing Thing rather than failing to re-provision a station")
    void reusesAnExistingThing() {
        Device device = unprovisioned("ESP32-001");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
        when(iotClient.createThing(any(CreateThingRequest.class)))
                .thenThrow(ResourceAlreadyExistsException.builder().message("thing exists").build());
        awsIssuesACertificate();

        // A decommissioned station keeps its Thing, and with it its shadow and
        // history; refusing here would make a station unrecoverable.
        DeviceProvisioningResponse response = service.provision("ESP32-001");

        assertThat(response.getCertificateId()).isEqualTo("cert-123");
        verify(iotClient).attachThingPrincipal(any(AttachThingPrincipalRequest.class));
    }

    // -------------------------------------------------------- refusals

    @Test
    @DisplayName("refuses to re-provision a station that still holds a live certificate")
    void refusesToReProvisionWithoutRevoking() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        // Issuing a second certificate would leave the first one active and
        // still able to publish as this station.
        assertThatThrownBy(() -> service.provision("ESP32-001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("Revoke it before re-provisioning");

        verify(iotClient, never()).createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class));
        verify(iotClient, never()).createThing(any(CreateThingRequest.class));
    }

    @Test
    @DisplayName("refuses an unknown device instead of minting an identity for it")
    void refusesAnUnknownDevice() {
        when(deviceRepository.findByDeviceUid("GHOST-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.provision("GHOST-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("GHOST-1");

        verify(iotClient, never()).createKeysAndCertificate(any(CreateKeysAndCertificateRequest.class));
    }

    // ------------------------------------------------- partial failure

    @Test
    @DisplayName("deletes the certificate it just created when a later step fails")
    void cleansUpAnOrphanedCertificate() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(unprovisioned("ESP32-001")));
        awsIssuesACertificate();
        when(iotClient.attachPolicy(any(AttachPolicyRequest.class))).thenThrow(awsRefuses("policy missing"));

        assertThatThrownBy(() -> service.provision("ESP32-001"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("policy missing");

        // An active certificate attached to nothing is a credential nobody is
        // tracking. It is deactivated before it is deleted.
        ArgumentCaptor<UpdateCertificateRequest> update = ArgumentCaptor.forClass(UpdateCertificateRequest.class);
        verify(iotClient).updateCertificate(update.capture());
        assertThat(update.getValue().certificateId()).isEqualTo("cert-123");
        assertThat(update.getValue().newStatusAsString()).isEqualTo("INACTIVE");
        verify(iotClient).deleteCertificate(any(DeleteCertificateRequest.class));
    }

    @Test
    @DisplayName("does not record a station as provisioned when provisioning failed")
    void doesNotPersistAFailedProvisioning() {
        Device device = unprovisioned("ESP32-001");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
        awsIssuesACertificate();
        when(iotClient.attachPolicy(any(AttachPolicyRequest.class))).thenThrow(awsRefuses("policy missing"));

        assertThatThrownBy(() -> service.provision("ESP32-001")).isInstanceOf(BusinessException.class);

        // A station recorded as PROVISIONED with a deleted certificate would be
        // refused a retry by the re-provisioning guard above, and would appear
        // in the console as holding an identity it does not have.
        assertThat(device.getCertificateId()).isNull();
        assertThat(device.getIotConnectionStatus()).isNull();
        verify(deviceRepository, never()).save(any(Device.class));
    }

    @Test
    @DisplayName("deletes nothing when the failure happened before a certificate existed")
    void noCleanupWhenNothingWasCreated() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(unprovisioned("ESP32-001")));
        when(iotClient.createThing(any(CreateThingRequest.class))).thenThrow(awsRefuses("registry unavailable"));

        assertThatThrownBy(() -> service.provision("ESP32-001")).isInstanceOf(BusinessException.class);

        verify(iotClient, never()).deleteCertificate(any(DeleteCertificateRequest.class));
        verify(iotClient, never()).updateCertificate(any(UpdateCertificateRequest.class));
    }

    // ------------------------------------------------------ revocation

    @Test
    @DisplayName("deactivates the certificate before detaching it")
    void deactivatesFirst() {
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(provisioned("ESP32-001")));

        service.revoke("ESP32-001");

        // Order is the whole point: after the first call the station can no
        // longer connect, even if every later call fails.
        InOrder order = inOrder(iotClient);
        order.verify(iotClient).updateCertificate(any(UpdateCertificateRequest.class));
        order.verify(iotClient).detachPolicy(any(DetachPolicyRequest.class));
        order.verify(iotClient).detachThingPrincipal(any(DetachThingPrincipalRequest.class));
        order.verify(iotClient).deleteCertificate(any(DeleteCertificateRequest.class));
    }

    @Test
    @DisplayName("clears the local record so the station can be provisioned again")
    void clearsTheLocalRecordOnRevoke() {
        Device device = provisioned("ESP32-001");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));

        service.revoke("ESP32-001");

        assertThat(device.getCertificateId()).isNull();
        assertThat(device.getCertificateArn()).isNull();
        assertThat(device.getIotConnectionStatus()).isEqualTo("REVOKED");
        verify(deviceRepository).save(device);
    }

    @Test
    @DisplayName("clears the local record even when AWS fails part-way through")
    void clearsTheLocalRecordEvenOnPartialFailure() {
        Device device = provisioned("ESP32-001");
        when(deviceRepository.findByDeviceUid("ESP32-001")).thenReturn(Optional.of(device));
        when(iotClient.detachPolicy(any(DetachPolicyRequest.class))).thenThrow(awsRefuses("detach failed"));

        assertThatThrownBy(() -> service.revoke("ESP32-001")).isInstanceOf(BusinessException.class);

        // The certificate is already INACTIVE, so keeping the stale reference
        // would only block a re-provision that is now the correct next step.
        assertThat(device.getCertificateId()).isNull();
        assertThat(device.getIotConnectionStatus()).isEqualTo("REVOKED");
        verify(deviceRepository).save(device);
    }

    @Test
    @DisplayName("refuses to revoke a station that has no certificate")
    void refusesToRevokeAnUnprovisionedStation() {
        when(deviceRepository.findByDeviceUid("ESP32-NEW")).thenReturn(Optional.of(unprovisioned("ESP32-NEW")));

        assertThatThrownBy(() -> service.revoke("ESP32-NEW"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("no certificate to revoke");

        verify(iotClient, never()).updateCertificate(any(UpdateCertificateRequest.class));
        verify(deviceRepository, never()).save(any(Device.class));
    }

    @Test
    @DisplayName("refuses to revoke an unknown device")
    void refusesToRevokeAnUnknownDevice() {
        when(deviceRepository.findByDeviceUid("GHOST-1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.revoke("GHOST-1"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("GHOST-1");

        verify(iotClient, never()).updateCertificate(any(UpdateCertificateRequest.class));
    }
}
