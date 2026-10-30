package com.weatherplatform.sensorservice.controller;

import com.weatherplatform.sensorservice.calibration.CalibrationService;
import com.weatherplatform.sensorservice.dto.ApiResponse;
import com.weatherplatform.sensorservice.dto.CalibrationRequest;
import com.weatherplatform.sensorservice.dto.CalibrationResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/sensors/{deviceUid}/calibrations")
@RequiredArgsConstructor
@Tag(name = "Calibration", description = "Sensor calibration records and the corrections applied to telemetry")
public class CalibrationController {

    private final CalibrationService calibrationService;

    @Operation(summary = "Calibration history",
            description = "Every calibration of the device's sensors, newest first. Only the newest per sensor is in "
                    + "force; older ones are SUPERSEDED. An empty list means the device has never been calibrated.")
    @GetMapping
    public ResponseEntity<ApiResponse<List<CalibrationResponse>>> history(@PathVariable String deviceUid) {
        return ResponseEntity.ok(ApiResponse.<List<CalibrationResponse>>builder()
                .success(true)
                .message("Calibration history retrieved successfully")
                .data(calibrationService.history(deviceUid))
                .path("/api/sensors/" + deviceUid + "/calibrations")
                .build());
    }

    @Operation(summary = "Record a calibration",
            description = "Pairs a reference instrument's reading with the sensor's own. Send only referenceValue to "
                    + "pair it with the sensor's latest raw reading (which must be under five minutes old); send "
                    + "sensorValue and calibratedAt to record one done earlier. The offset is computed, never "
                    + "supplied. 422 when the offset is implausibly large or the reading to pair with is missing "
                    + "or stale.")
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<CalibrationResponse>> record(@PathVariable String deviceUid,
                                                                   @Valid @RequestBody CalibrationRequest request) {
        CalibrationResponse saved = calibrationService.record(deviceUid, request, currentUser());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<CalibrationResponse>builder()
                .success(true)
                .message("Calibration recorded; offset " + saved.getOffset().toPlainString()
                        + " now applies to new " + saved.getSensorType() + " readings")
                .data(saved)
                .path("/api/sensors/" + deviceUid + "/calibrations")
                .build());
    }

    /** The operator's email as forwarded by the gateway, else their user id. */
    private static String currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) return null;
        if (auth.getDetails() instanceof String email && !email.isBlank()) return email;
        return auth.getName();
    }
}
