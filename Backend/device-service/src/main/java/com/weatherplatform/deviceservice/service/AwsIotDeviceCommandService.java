package com.weatherplatform.deviceservice.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.deviceservice.config.IotProperties;
import com.weatherplatform.deviceservice.dto.DeviceCommandResponse;
import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceCommand;
import com.weatherplatform.deviceservice.exception.BusinessException;
import com.weatherplatform.deviceservice.exception.ResourceNotFoundException;
import com.weatherplatform.deviceservice.repository.DeviceRepository;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.iotdataplane.IotDataPlaneClient;
import software.amazon.awssdk.services.iotdataplane.model.PublishRequest;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Publishes authorized commands to a station's own MQTT topic.
 *
 * The command set is closed ({@link DeviceCommand}) and the topic is derived
 * from the device UID, so an operator cannot address a topic outside the
 * station's own namespace even if they craft the request by hand.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "aws.iot.enabled", havingValue = "true")
public class AwsIotDeviceCommandService implements DeviceCommandService {

    private final IotDataPlaneClient iotDataPlaneClient;
    private final DeviceRepository deviceRepository;
    private final IotProperties iotProperties;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    @Override
    @Transactional(readOnly = true)
    @CircuitBreaker(name = "awsIot", fallbackMethod = "iotUnavailable")
    public DeviceCommandResponse send(String deviceUid, DeviceCommand command, String issuedBy) {
        Device device = deviceRepository.findByDeviceUid(deviceUid)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found for UID: " + deviceUid));

        if (device.getCertificateId() == null) {
            // An unprovisioned station has no identity and is not subscribed to
            // anything; publishing would succeed silently and mislead the operator.
            throw new BusinessException(
                    "Device " + deviceUid + " has no IoT identity. Provision it before sending commands.");
        }

        String commandId = UUID.randomUUID().toString();
        String topic = iotProperties.otaTopic(deviceUid);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("commandId", commandId);
        payload.put("command", command.name());
        payload.put("issuedAt", Instant.now().toString());
        // Recorded for the audit trail on the device side; identity only, no token.
        payload.put("issuedBy", issuedBy);

        try {
            iotDataPlaneClient.publish(PublishRequest.builder()
                    .topic(topic)
                    // QoS 1: at-least-once. The device deduplicates on commandId,
                    // so a redelivered REBOOT does not reboot twice.
                    .qos(1)
                    .payload(SdkBytes.fromUtf8String(objectMapper.writeValueAsString(payload)))
                    .build());

            meterRegistry.counter("device.commands.published", "command", command.name()).increment();
            log.info("Command {} ({}) published to {} by {}", command, commandId, topic, issuedBy);

            return DeviceCommandResponse.builder()
                    .deviceUid(deviceUid)
                    .command(command)
                    .commandId(commandId)
                    .status("PUBLISHED")
                    .topic(topic)
                    .issuedAt(Instant.now())
                    .build();

        } catch (JsonProcessingException e) {
            throw new BusinessException("Failed to serialise command payload");
        }
    }

    /**
     * Circuit-breaker fallback: report the outage plainly rather than letting the
     * console imply the station received something it never did.
     */
    @SuppressWarnings("unused")
    private DeviceCommandResponse iotUnavailable(String deviceUid, DeviceCommand command,
                                                 String issuedBy, Throwable cause) {
        meterRegistry.counter("device.commands.failed", "command", command.name()).increment();
        log.error("AWS IoT unavailable while sending {} to {}", command, deviceUid, cause);
        throw new BusinessException(
                "AWS IoT is currently unreachable, so the command was not delivered. Please retry shortly.");
    }
}
