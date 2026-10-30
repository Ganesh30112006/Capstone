package com.weatherplatform.sensorservice.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class SensorStatisticsResponse {
    String deviceUid;
    long totalReadings;
    BigDecimal avgTemperature;
    BigDecimal minTemperature;
    BigDecimal maxTemperature;
    BigDecimal avgHumidity;
    BigDecimal minHumidity;
    BigDecimal maxHumidity;
    Double avgAirQuality;
    Integer minAirQuality;
    Integer maxAirQuality;
    BigDecimal avgPressure;
    BigDecimal minPressure;
    BigDecimal maxPressure;
}
