package com.weatherplatform.deviceservice.service;

import com.weatherplatform.deviceservice.config.IotProperties;
import com.weatherplatform.deviceservice.dto.DeviceProvisioningResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.exception.BusinessException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import software.amazon.awssdk.services.iot.model.ResourceAlreadyExistsException;
import software.amazon.awssdk.services.iot.model.UpdateCertificateRequest;

import java.time.Instant;

/**
 * AWS IoT Core implementation of device provisioning.
 *
 * Enabled only when {@code aws.iot.enabled=true}, so local development runs
 * without AWS credentials and without silently pretending a device has a real
 * identity.
 *
 * Ordering matters here. The certificate is created first, then attached to the
 * Thing and the policy, and only then recorded in PostgreSQL. If any step
 * fails, the partially created certificate is cleaned up rather than left
 * orphaned in the AWS account.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aws.iot.enabled", havingValue = "true")
public class AwsIotDeviceProvisioningService implements DeviceProvisioningService {

    private final IotClient iotClient;
    private final DeviceRepository deviceRepository;
    private final IotProperties iotProperties;

    @Override
    @Transactional
    public DeviceProvisioningResponse provision(String deviceUid) {
        Device device = deviceRepository.findByDeviceUid(deviceUid)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found for UID: " + deviceUid));

        if (device.getCertificateId() != null) {
            // Re-provisioning silently would leave the previous certificate live
            // and able to publish — an explicit revoke has to come first.
            throw new BusinessException(
                    "Device " + deviceUid + " already has an active certificate. Revoke it before re-provisioning.");
        }

        String thingName = iotProperties.thingNameFor(deviceUid);
        CreateKeysAndCertificateResponse certificate = null;

        try {
            createThingIfAbsent(thingName, deviceUid);

            // AWS generates the key pair; the private key exists in this response
            // and nowhere else in the platform, ever.
            certificate = iotClient.createKeysAndCertificate(
                    CreateKeysAndCertificateRequest.builder().setAsActive(true).build());

            iotClient.attachThingPrincipal(AttachThingPrincipalRequest.builder()
                    .thingName(thingName)
                    .principal(certificate.certificateArn())
                    .build());

            // The policy is least-privilege and topic-scoped; see the Terraform
            // definition in infra/terraform/iot.tf.
            iotClient.attachPolicy(AttachPolicyRequest.builder()
                    .policyName(iotProperties.getPolicyName())
                    .target(certificate.certificateArn())
                    .build());

            device.setThingName(thingName);
            device.setCertificateId(certificate.certificateId());
            device.setCertificateArn(certificate.certificateArn());
            device.setIotConnectionStatus("PROVISIONED");
            deviceRepository.save(device);

            // Logged as an audit event — identifiers only, never the key material.
            log.info("Provisioned IoT identity for {}: thing={} certificateId={}",
                    deviceUid, thingName, certificate.certificateId());

            return DeviceProvisioningResponse.builder()
                    .deviceUid(deviceUid)
                    .thingName(thingName)
                    .certificateId(certificate.certificateId())
                    .certificateArn(certificate.certificateArn())
                    .certificatePem(certificate.certificatePem())
                    .privateKey(certificate.keyPair().privateKey())
                    .mqttEndpoint(iotProperties.getMqttEndpoint())
                    .telemetryTopic(iotProperties.telemetryTopic(deviceUid))
                    .heartbeatTopic(iotProperties.heartbeatTopic(deviceUid))
                    .otaTopic(iotProperties.otaTopic(deviceUid))
                    .provisionedAt(Instant.now())
                    .build();

        } catch (IotException e) {
            // Do not leave a live certificate behind that nothing references.
            cleanUpOrphanedCertificate(certificate);
            log.error("IoT provisioning failed for {}: {}", deviceUid, e.awsErrorDetails().errorMessage());
            throw new BusinessException("Failed to provision device identity: " + e.awsErrorDetails().errorMessage());
        }
    }

    @Override
    @Transactional
    public void revoke(String deviceUid) {
        Device device = deviceRepository.findByDeviceUid(deviceUid)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found for UID: " + deviceUid));

        if (device.getCertificateId() == null) {
            throw new BusinessException("Device " + deviceUid + " has no certificate to revoke.");
        }

        String certificateId = device.getCertificateId();
        String certificateArn = device.getCertificateArn();

        try {
            // Deactivate first: from this moment the device can no longer connect,
            // even if the later detach/delete calls fail.
            iotClient.updateCertificate(UpdateCertificateRequest.builder()
                    .certificateId(certificateId)
                    .newStatus("INACTIVE")
                    .build());

            iotClient.detachPolicy(DetachPolicyRequest.builder()
                    .policyName(iotProperties.getPolicyName())
                    .target(certificateArn)
                    .build());

            iotClient.detachThingPrincipal(DetachThingPrincipalRequest.builder()
                    .thingName(device.getThingName())
                    .principal(certificateArn)
                    .build());

            iotClient.deleteCertificate(DeleteCertificateRequest.builder()
                    .certificateId(certificateId)
                    .forceDelete(true)
                    .build());

            log.info("Revoked IoT certificate {} for device {}", certificateId, deviceUid);

        } catch (IotException e) {
            log.error("Failed to fully revoke certificate {} for {}: {}",
                    certificateId, deviceUid, e.awsErrorDetails().errorMessage());
            throw new BusinessException("Failed to revoke device identity: " + e.awsErrorDetails().errorMessage());
        } finally {
            // Clear the local record regardless: the certificate is deactivated,
            // so leaving a stale reference would only block re-provisioning.
            device.setCertificateId(null);
            device.setCertificateArn(null);
            device.setIotConnectionStatus("REVOKED");
            deviceRepository.save(device);
        }
    }

    private void createThingIfAbsent(String thingName, String deviceUid) {
        try {
            iotClient.createThing(CreateThingRequest.builder()
                    .thingName(thingName)
                    .thingTypeName(iotProperties.getThingTypeName())
                    .attributePayload(b -> b.attributes(java.util.Map.of("deviceUid", deviceUid)))
                    .build());
        } catch (ResourceAlreadyExistsException e) {
            // Re-provisioning a decommissioned station reuses its Thing, which
            // keeps its shadow and history intact.
            log.debug("IoT Thing {} already exists; reusing it", thingName);
        }
    }

    private void cleanUpOrphanedCertificate(CreateKeysAndCertificateResponse certificate) {
        if (certificate == null) {
            return;
        }
        try {
            iotClient.updateCertificate(UpdateCertificateRequest.builder()
                    .certificateId(certificate.certificateId())
                    .newStatus("INACTIVE")
                    .build());
            iotClient.deleteCertificate(DeleteCertificateRequest.builder()
                    .certificateId(certificate.certificateId())
                    .forceDelete(true)
                    .build());
            log.info("Cleaned up orphaned certificate {}", certificate.certificateId());
        } catch (Exception cleanupError) {
            // Surfaced loudly: an active certificate with no Thing is a security
            // finding that needs a human, and it must not mask the original error.
            log.error("ORPHANED CERTIFICATE {} could not be removed and may still be active. "
                    + "Revoke it manually in the AWS IoT console.", certificate.certificateId(), cleanupError);
        }
    }
}
