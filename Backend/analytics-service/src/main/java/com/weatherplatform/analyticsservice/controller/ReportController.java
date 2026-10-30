package com.weatherplatform.analyticsservice.controller;

import com.weatherplatform.analyticsservice.dto.ApiResponse;
import com.weatherplatform.analyticsservice.dto.EnvironmentalReportResponse;
import com.weatherplatform.analyticsservice.dto.FleetReportResponse;
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

@Slf4j
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
@Tag(name = "Environmental Reports", description = "Endpoints for generating historical environmental reports for stations and fleets")
public class ReportController {

    private final AnalyticsService analyticsService;

    @Operation(summary = "Get Daily Report", description = "Generate a daily environmental report for a specific device")
    @GetMapping("/{deviceUid}/daily")
    public ResponseEntity<ApiResponse<EnvironmentalReportResponse>> getDailyReport(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("GET /api/reports/{}/daily?date={}", deviceUid, date);
        EnvironmentalReportResponse report = analyticsService.getDailyReport(deviceUid, date);
        return ResponseEntity.ok(ApiResponse.<EnvironmentalReportResponse>builder()
                .success(true).message("Daily environmental report generated").data(report)
                .path("/api/reports/" + deviceUid + "/daily").build());
    }

    @Operation(summary = "Get Weekly Report", description = "Generate a 7-day environmental report for a specific device")
    @GetMapping("/{deviceUid}/weekly")
    public ResponseEntity<ApiResponse<EnvironmentalReportResponse>> getWeeklyReport(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("GET /api/reports/{}/weekly?endDate={}", deviceUid, endDate);
        EnvironmentalReportResponse report = analyticsService.getWeeklyReport(deviceUid, endDate);
        return ResponseEntity.ok(ApiResponse.<EnvironmentalReportResponse>builder()
                .success(true).message("Weekly environmental report generated").data(report)
                .path("/api/reports/" + deviceUid + "/weekly").build());
    }

    @Operation(summary = "Get Monthly Report", description = "Generate a 30-day environmental report for a specific device")
    @GetMapping("/{deviceUid}/monthly")
    public ResponseEntity<ApiResponse<EnvironmentalReportResponse>> getMonthlyReport(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("GET /api/reports/{}/monthly?endDate={}", deviceUid, endDate);
        EnvironmentalReportResponse report = analyticsService.getMonthlyReport(deviceUid, endDate);
        return ResponseEntity.ok(ApiResponse.<EnvironmentalReportResponse>builder()
                .success(true).message("Monthly environmental report generated").data(report)
                .path("/api/reports/" + deviceUid + "/monthly").build());
    }

    @Operation(summary = "Get Custom Range Report", description = "Generate an environmental report for a custom date range")
    @GetMapping("/{deviceUid}/custom")
    public ResponseEntity<ApiResponse<EnvironmentalReportResponse>> getCustomReport(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        log.info("GET /api/reports/{}/custom (from={}, to={})", deviceUid, from, to);
        EnvironmentalReportResponse report = analyticsService.getCustomReport(deviceUid, from, to);
        return ResponseEntity.ok(ApiResponse.<EnvironmentalReportResponse>builder()
                .success(true).message("Custom environmental report generated").data(report)
                .path("/api/reports/" + deviceUid + "/custom").build());
    }

    @Operation(summary = "Get Fleet Daily Report", description = "Generate a daily report aggregated across all stations in the fleet")
    @GetMapping("/fleet/daily")
    public ResponseEntity<ApiResponse<FleetReportResponse>> getFleetDailyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        log.info("GET /api/reports/fleet/daily?date={}", date);
        FleetReportResponse report = analyticsService.getFleetDailyReport(date);
        return ResponseEntity.ok(ApiResponse.<FleetReportResponse>builder()
                .success(true).message("Fleet daily report generated").data(report)
                .path("/api/reports/fleet/daily").build());
    }

    @Operation(summary = "Get Fleet Weekly Report", description = "Generate a weekly report aggregated across all stations in the fleet")
    @GetMapping("/fleet/weekly")
    public ResponseEntity<ApiResponse<FleetReportResponse>> getFleetWeeklyReport(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate) {
        log.info("GET /api/reports/fleet/weekly?endDate={}", endDate);
        FleetReportResponse report = analyticsService.getFleetWeeklyReport(endDate);
        return ResponseEntity.ok(ApiResponse.<FleetReportResponse>builder()
                .success(true).message("Fleet weekly report generated").data(report)
                .path("/api/reports/fleet/weekly").build());
    }
}
