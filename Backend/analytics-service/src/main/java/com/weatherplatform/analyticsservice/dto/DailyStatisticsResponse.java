package com.weatherplatform.analyticsservice.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;

@Value
@Builder
public class DailyStatisticsResponse {
    String deviceUid;
    LocalDate date;
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
    BigDecimal avgOxygen;
    BigDecimal minOxygen;
    BigDecimal maxOxygen;
    Integer rainEventsCount;
    Integer readingCount;
}
