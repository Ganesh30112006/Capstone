package com.weatherplatform.deviceservice.dto;

import com.weatherplatform.deviceservice.entity.DeviceStatus;
import lombok.Builder;
import lombok.Value;

import java.time.Instant;

/**
 * Sanitised station projection served to unauthenticated public visitors.
 *
 * Deliberately omits everything operational: battery level, Wi-Fi strength,
 * firmware version, IoT Thing name, certificate identifiers, and internal row
 * IDs. Adding a field here makes it world-readable, so treat it as a published
 * contract rather than a convenience DTO.
 */
@Value
@Builder
public class PublicStationResponse {

    String stationId;
    String name;
    String location;
    Double latitude;
    Double longitude;
    DeviceStatus status;
    Instant lastReportedAt;
}
