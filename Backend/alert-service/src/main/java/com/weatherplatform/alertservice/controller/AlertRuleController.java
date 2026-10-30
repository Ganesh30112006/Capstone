package com.weatherplatform.alertservice.controller;

import com.weatherplatform.alertservice.dto.AlertRuleRequest;
import com.weatherplatform.alertservice.dto.AlertRuleResponse;
import com.weatherplatform.alertservice.dto.ApiResponse;
import com.weatherplatform.alertservice.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/alerts/rules")
@RequiredArgsConstructor
@Tag(name = "Alert Rules", description = "Endpoints for managing alert rules")
public class AlertRuleController {

    private final AlertService alertService;

    @Operation(summary = "Create Alert Rule", description = "Create a new alert rule for a device")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<AlertRuleResponse>> createRule(@Valid @RequestBody AlertRuleRequest request) {
        log.info("POST /api/alerts/rules for device: {}", request.getDeviceUid());
        AlertRuleResponse response = alertService.createRule(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<AlertRuleResponse>builder()
                .success(true).message("Alert rule created").data(response)
                .path("/api/alerts/rules").build());
    }

    @Operation(summary = "Get Rules for Device", description = "Get all alert rules for a specific device")
    @GetMapping("/device/{deviceUid}")
    public ResponseEntity<ApiResponse<List<AlertRuleResponse>>> getRulesForDevice(@PathVariable String deviceUid) {
        log.info("GET /api/alerts/rules/device/{}", deviceUid);
        List<AlertRuleResponse> rules = alertService.getRulesForDevice(deviceUid);
        return ResponseEntity.ok(ApiResponse.<List<AlertRuleResponse>>builder()
                .success(true).message("Alert rules retrieved").data(rules)
                .path("/api/alerts/rules/device/" + deviceUid).build());
    }

    @Operation(summary = "Delete Alert Rule", description = "Delete an alert rule by ID")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{ruleId}")
    public ResponseEntity<ApiResponse<Void>> deleteRule(@PathVariable Long ruleId) {
        log.info("DELETE /api/alerts/rules/{}", ruleId);
        alertService.deleteRule(ruleId);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true).message("Alert rule deleted")
                .path("/api/alerts/rules/" + ruleId).build());
    }
}
