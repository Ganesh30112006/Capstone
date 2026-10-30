package com.weatherplatform.deviceservice.controller;

import com.weatherplatform.deviceservice.dto.ApiResponse;
import com.weatherplatform.deviceservice.dto.FirmwareCreateRequest;
import com.weatherplatform.deviceservice.dto.FirmwareResponse;
import com.weatherplatform.deviceservice.service.FirmwareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/firmware")
@PreAuthorize("hasRole('ADMIN')") // Firmware catalogue is administrator-only in its entirety
@RequiredArgsConstructor
@Tag(name = "Firmware Management", description = "Endpoints for managing OTA firmware versions and binaries")
public class FirmwareController {

    private final FirmwareService firmwareService;

    @Operation(summary = "Create Firmware Version", description = "Register a new firmware release with metadata and S3 URL")
    @PostMapping
    public ResponseEntity<ApiResponse<FirmwareResponse>> createFirmware(@Valid @RequestBody FirmwareCreateRequest request) {
        log.info("POST /api/firmware — version: {}", request.getVersion());
        FirmwareResponse response = firmwareService.createFirmware(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<FirmwareResponse>builder()
                .success(true)
                .message("Firmware created successfully")
                .data(response)
                .path("/api/firmware")
                .build());
    }

    @Operation(summary = "Get All Firmware", description = "List all registered firmware releases")
    @GetMapping
    public ResponseEntity<ApiResponse<List<FirmwareResponse>>> getAllFirmware() {
        log.info("GET /api/firmware");
        List<FirmwareResponse> list = firmwareService.getAllFirmware();
        return ResponseEntity.ok(ApiResponse.<List<FirmwareResponse>>builder()
                .success(true)
                .message("Firmware list retrieved successfully")
                .data(list)
                .path("/api/firmware")
                .build());
    }

    @Operation(summary = "Get Firmware by Version", description = "Retrieve specific firmware release metadata")
    @GetMapping("/{version}")
    public ResponseEntity<ApiResponse<FirmwareResponse>> getFirmwareByVersion(@PathVariable String version) {
        log.info("GET /api/firmware/{}", version);
        FirmwareResponse response = firmwareService.getFirmwareByVersion(version);
        return ResponseEntity.ok(ApiResponse.<FirmwareResponse>builder()
                .success(true)
                .message("Firmware metadata retrieved successfully")
                .data(response)
                .path("/api/firmware/" + version)
                .build());
    }
}
