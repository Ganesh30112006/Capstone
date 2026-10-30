package com.weatherplatform.alertservice.controller;

import com.weatherplatform.alertservice.dto.AlertResponse;
import com.weatherplatform.alertservice.dto.AnomalyResponse;
import com.weatherplatform.alertservice.dto.AlertStatusUpdateRequest;
import com.weatherplatform.alertservice.dto.ApiResponse;
import com.weatherplatform.alertservice.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/alerts")
@RequiredArgsConstructor
@Tag(name = "Alerts", description = "Endpoints for viewing and managing triggered alerts")
public class AlertController {

    private final AlertService alertService;

    @Operation(summary = "Get Alerts for Device", description = "Get alerts for a specific device, optionally filtered by status")
    @GetMapping("/device/{deviceUid}")
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getAlertsForDevice(
            @PathVariable String deviceUid,
            @RequestParam(required = false) String status) {
        log.info("GET /api/alerts/device/{}?status={}", deviceUid, status);
        List<AlertResponse> alerts = alertService.getAlertsForDevice(deviceUid, status);
        return ResponseEntity.ok(ApiResponse.<List<AlertResponse>>builder()
                .success(true).message("Alerts retrieved").data(alerts)
                .path("/api/alerts/device/" + deviceUid).build());
    }

    @Operation(summary = "Get All Alerts", description = "Get network-wide alerts, optionally filtered by status")
    @GetMapping
    public ResponseEntity<ApiResponse<List<AlertResponse>>> getAllAlerts(
            @RequestParam(required = false) String status) {
        log.info("GET /api/alerts?status={}", status);
        List<AlertResponse> alerts = alertService.getAllAlerts(status);
        return ResponseEntity.ok(ApiResponse.<List<AlertResponse>>builder()
                .success(true).message("Alerts retrieved").data(alerts)
                .path("/api/alerts").build());
    }

    @Operation(summary = "Get All Anomalies", description = "Get network-wide AI anomalies")
    @GetMapping("/anomalies")
    public ResponseEntity<ApiResponse<List<AnomalyResponse>>> getAnomalies() {
        log.info("GET /api/alerts/anomalies");
        List<AnomalyResponse> anomalies = alertService.getAnomalies();
        return ResponseEntity.ok(ApiResponse.<List<AnomalyResponse>>builder()
                .success(true).message("Anomalies retrieved").data(anomalies)
                .path("/api/alerts/anomalies").build());
    }

    @Operation(summary = "Update Alert Status", description = "Update the status of a triggered alert (e.g., RESOLVED, DISMISSED)")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{alertId}/status")
    public ResponseEntity<ApiResponse<AlertResponse>> updateAlertStatus(
            @PathVariable Long alertId,
            @Valid @RequestBody AlertStatusUpdateRequest request) {
        log.info("PATCH /api/alerts/{}/status", alertId);
        AlertResponse response = alertService.updateAlertStatus(alertId, request);
        return ResponseEntity.ok(ApiResponse.<AlertResponse>builder()
                .success(true).message("Alert status updated").data(response)
                .path("/api/alerts/" + alertId + "/status").build());
    }
}
