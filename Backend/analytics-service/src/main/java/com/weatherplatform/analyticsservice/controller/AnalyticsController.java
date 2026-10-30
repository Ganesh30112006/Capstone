package com.weatherplatform.analyticsservice.controller;

import com.weatherplatform.analyticsservice.dto.ApiResponse;
import com.weatherplatform.analyticsservice.dto.DailyStatisticsResponse;
import com.weatherplatform.analyticsservice.dto.HeatmapResponse;
import com.weatherplatform.analyticsservice.dto.NetworkTrendResponse;
import com.weatherplatform.analyticsservice.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/analytics")
@RequiredArgsConstructor
@Tag(name = "Analytics", description = "Historical weather analytics, aggregated statistics, and environmental heatmaps")
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "Get Environmental Heatmap", description = "Get geographically distributed sensor readings across all stations for heatmap rendering")
    @GetMapping("/heatmap")
    public ResponseEntity<ApiResponse<HeatmapResponse>> getHeatmap(
            @Parameter(description = "Environmental parameter (temperature, humidity, airQuality, oxygen, pressure)")
            @RequestParam(defaultValue = "temperature") String parameter,
            @RequestParam(required = false) Double latitude,
            @RequestParam(required = false) Double longitude,
            @RequestParam(required = false) Double radius) {
        log.info("GET /api/analytics/heatmap?parameter={}&lat={}&lon={}&radius={}", parameter, latitude, longitude, radius);
        HeatmapResponse response = analyticsService.getHeatmap(parameter, latitude, longitude, radius);
        return ResponseEntity.ok(ApiResponse.<HeatmapResponse>builder()
                .success(true).message("Heatmap data retrieved successfully").data(response)
                .path("/api/analytics/heatmap").build());
    }

    @Operation(summary = "Get Network Trend",
            description = "Fleet-wide daily averages over the last 7 days, weighted by reading count. "
                    + "One point per day for which statistics exist; an empty list when none do. "
                    + "This service stores daily aggregates only, so no intra-day resolution is available here.")
    @GetMapping("/network/trend")
    public ResponseEntity<ApiResponse<List<NetworkTrendResponse>>> getNetworkTrend() {
        log.info("GET /api/analytics/network/trend");
        List<NetworkTrendResponse> trend = analyticsService.getNetworkTrend();
        return ResponseEntity.ok(ApiResponse.<List<NetworkTrendResponse>>builder()
                .success(true).message("Network trend retrieved successfully").data(trend)
                .path("/api/analytics/network/trend").build());
    }

    @Operation(summary = "Get Daily Statistics", description = "Aggregated min/max/avg for a specific date range")
    @GetMapping("/{deviceUid}/daily")
    public ResponseEntity<ApiResponse<List<DailyStatisticsResponse>>> getDaily(
            @Parameter(description = "Device UID") @PathVariable String deviceUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.info("GET /api/analytics/{}/daily (from={}, to={})", deviceUid, from, to);
        List<DailyStatisticsResponse> stats = analyticsService.getDailyStatistics(deviceUid, from, to);
        return ResponseEntity.ok(ApiResponse.<List<DailyStatisticsResponse>>builder()
                .success(true).message("Daily statistics retrieved").data(stats)
                .path("/api/analytics/" + deviceUid + "/daily").build());
    }

    @Operation(summary = "Get Weekly Statistics", description = "Daily breakdown for the last 7 days")
    @GetMapping("/{deviceUid}/weekly")
    public ResponseEntity<ApiResponse<List<DailyStatisticsResponse>>> getWeekly(
            @PathVariable String deviceUid) {
        log.info("GET /api/analytics/{}/weekly", deviceUid);
        List<DailyStatisticsResponse> stats = analyticsService.getWeeklyStatistics(deviceUid);
        return ResponseEntity.ok(ApiResponse.<List<DailyStatisticsResponse>>builder()
                .success(true).message("Weekly statistics retrieved").data(stats)
                .path("/api/analytics/" + deviceUid + "/weekly").build());
    }

    @Operation(summary = "Get Monthly Statistics", description = "Daily breakdown for the last 30 days")
    @GetMapping("/{deviceUid}/monthly")
    public ResponseEntity<ApiResponse<List<DailyStatisticsResponse>>> getMonthly(
            @PathVariable String deviceUid) {
        log.info("GET /api/analytics/{}/monthly", deviceUid);
        List<DailyStatisticsResponse> stats = analyticsService.getMonthlyStatistics(deviceUid);
        return ResponseEntity.ok(ApiResponse.<List<DailyStatisticsResponse>>builder()
                .success(true).message("Monthly statistics retrieved").data(stats)
                .path("/api/analytics/" + deviceUid + "/monthly").build());
    }
}
