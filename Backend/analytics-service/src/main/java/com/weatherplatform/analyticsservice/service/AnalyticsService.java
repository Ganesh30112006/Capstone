package com.weatherplatform.analyticsservice.service;

import com.weatherplatform.analyticsservice.dto.DailyStatisticsResponse;
import com.weatherplatform.analyticsservice.dto.EnvironmentalReportResponse;
import com.weatherplatform.analyticsservice.dto.FleetReportResponse;
import com.weatherplatform.analyticsservice.dto.HeatmapResponse;
import com.weatherplatform.analyticsservice.dto.NetworkTrendResponse;
import com.weatherplatform.analyticsservice.event.SensorReadingEvent;

import java.time.LocalDate;
import java.util.List;

public interface AnalyticsService {

    void processSensorReading(SensorReadingEvent event);

    List<DailyStatisticsResponse> getDailyStatistics(String deviceUid, LocalDate from, LocalDate to);

    List<DailyStatisticsResponse> getWeeklyStatistics(String deviceUid);

    List<DailyStatisticsResponse> getMonthlyStatistics(String deviceUid);

    HeatmapResponse getHeatmap(String parameter, Double latitude, Double longitude, Double radiusKm);

    List<NetworkTrendResponse> getNetworkTrend();

    EnvironmentalReportResponse getDailyReport(String deviceUid, LocalDate date);

    EnvironmentalReportResponse getWeeklyReport(String deviceUid, LocalDate endDate);

    EnvironmentalReportResponse getMonthlyReport(String deviceUid, LocalDate endDate);

    EnvironmentalReportResponse getCustomReport(String deviceUid, LocalDate from, LocalDate to);

    FleetReportResponse getFleetDailyReport(LocalDate date);

    FleetReportResponse getFleetWeeklyReport(LocalDate endDate);
}
