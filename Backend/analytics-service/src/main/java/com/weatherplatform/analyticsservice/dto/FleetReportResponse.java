package com.weatherplatform.analyticsservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class FleetReportResponse {
    String reportType;
    LocalDate startDate;
    LocalDate endDate;
    Integer totalStations;
    Integer totalReadings;
    BigDecimal fleetAvgTemperature;
    BigDecimal fleetAvgHumidity;
    Double fleetAvgAirQuality;
    BigDecimal fleetAvgOxygen;
    BigDecimal fleetAvgPressure;
    Integer totalRainEvents;
    List<EnvironmentalReportResponse> stationReports;
}
