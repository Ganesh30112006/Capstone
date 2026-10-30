package com.weatherplatform.deviceservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.util.Map;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DigitalTwinResponse {
    String deviceUid;
    String deviceName;
    LocationInfo location;
    String status;
    String firmware;
    HealthInfo health;
    Map<String, SensorStateDTO> sensors;

    @Value
    @Builder
    public static class LocationInfo {
        Double latitude;
        Double longitude;
        String name;
    }

    @Value
    @Builder
    public static class HealthInfo {
        Integer battery;
        Integer wifiSignal;
    }
}
