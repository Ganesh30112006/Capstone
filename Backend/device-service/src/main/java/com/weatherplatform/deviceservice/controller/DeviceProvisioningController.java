package com.weatherplatform.deviceservice.controller;

import com.weatherplatform.deviceservice.dto.ApiResponse;
import com.weatherplatform.deviceservice.dto.DeviceProvisioningResponse;
import com.weatherplatform.deviceservice.service.DeviceProvisioningService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issues and revokes a station's AWS IoT identity.
 *
 * Administrator-only, and never reachable from a public route. The provisioning
 * response contains a private key, so it is deliberately a POST that returns the
 * material exactly once — there is no GET that can re-read it, by design.
 */
@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceUid}/provisioning")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Device Provisioning", description = "AWS IoT Thing and X.509 certificate lifecycle")
public class DeviceProvisioningController {

    private final DeviceProvisioningService provisioningService;

    @Operation(summary = "Provision Device Identity",
            description = "Creates the IoT Thing, key pair and certificate. The private key is returned once and never stored.")
    @PostMapping
    public ResponseEntity<ApiResponse<DeviceProvisioningResponse>> provision(@PathVariable String deviceUid) {
        log.info("POST /api/devices/{}/provisioning", deviceUid);

        DeviceProvisioningResponse response = provisioningService.provision(deviceUid);

        return ResponseEntity.status(HttpStatus.CREATED)
                // Key material must not sit in a proxy or browser cache.
                .header("Cache-Control", "no-store")
                .header("Pragma", "no-cache")
                .body(ApiResponse.<DeviceProvisioningResponse>builder()
                        .success(true)
                        .message("Device provisioned. Store the private key now — it cannot be retrieved again.")
                        .data(response)
                        .path("/api/devices/" + deviceUid + "/provisioning")
                        .build());
    }

    @Operation(summary = "Revoke Device Identity",
            description = "Deactivates and deletes the device certificate so it can no longer connect")
    @DeleteMapping
    public ResponseEntity<ApiResponse<Void>> revoke(@PathVariable String deviceUid) {
        log.info("DELETE /api/devices/{}/provisioning", deviceUid);

        provisioningService.revoke(deviceUid);

        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Device certificate revoked")
                .path("/api/devices/" + deviceUid + "/provisioning")
                .build());
    }
}
