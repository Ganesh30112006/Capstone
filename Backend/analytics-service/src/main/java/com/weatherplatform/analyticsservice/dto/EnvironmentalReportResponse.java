package com.weatherplatform.analyticsservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EnvironmentalReportResponse {
    String deviceUid;
    String reportType;
    LocalDate startDate;
    LocalDate endDate;
    Integer totalReadings;
    Double uptimePercentage;

    // Temperature
    BigDecimal minTemperature;
    BigDecimal maxTemperature;
    BigDecimal avgTemperature;

    // Humidity
    BigDecimal minHumidity;
    BigDecimal maxHumidity;
    BigDecimal avgHumidity;

    // Air Quality
    Integer minAirQuality;
    Integer maxAirQuality;
    Double avgAirQuality;

    // Oxygen
    BigDecimal minOxygen;
    BigDecimal maxOxygen;
    BigDecimal avgOxygen;

    // Pressure
    BigDecimal minPressure;
    BigDecimal maxPressure;
    BigDecimal avgPressure;

    // Events
    Integer rainEvents;
    Integer totalAlerts;
    Integer totalAnomalies;
    Integer totalSensorFailures;
}
