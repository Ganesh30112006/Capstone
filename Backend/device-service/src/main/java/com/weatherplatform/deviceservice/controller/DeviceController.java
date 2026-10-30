package com.weatherplatform.deviceservice.controller;

import com.weatherplatform.deviceservice.dto.ApiResponse;
import com.weatherplatform.deviceservice.dto.DeviceFirmwareUpdateResponse;
import com.weatherplatform.deviceservice.dto.DeviceHealthResponse;
import com.weatherplatform.deviceservice.dto.DeviceHeartbeatRequest;
import com.weatherplatform.deviceservice.dto.DeviceLocationResponse;
import com.weatherplatform.deviceservice.dto.DeviceLocationUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceRegistrationRequest;
import com.weatherplatform.deviceservice.dto.DeviceResponse;
import com.weatherplatform.deviceservice.dto.DeviceStatusUpdateRequest;
import com.weatherplatform.deviceservice.dto.DeviceUpdateRequest;
import com.weatherplatform.deviceservice.dto.DigitalTwinResponse;
import com.weatherplatform.deviceservice.dto.FirmwareUpdateRequest;
import com.weatherplatform.deviceservice.dto.FirmwareUpdateStatusRequest;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import com.weatherplatform.deviceservice.service.DeviceService;
import com.weatherplatform.deviceservice.service.DigitalTwinService;
import com.weatherplatform.deviceservice.service.FirmwareService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@Slf4j
@RestController
@RequestMapping("/api/devices")
@RequiredArgsConstructor
@Tag(name = "Device Management", description = "Endpoints for registering and managing IoT weather station devices")
public class DeviceController {

    /*
     * Authorization model for this controller:
     *   read  (GET)                  — any authenticated user
     *   write (POST/PUT/PATCH/DELETE) — ADMIN only
     *
     * The gateway applies the same policy first; these annotations are the
     * authoritative check so a gateway misconfiguration cannot grant a USER
     * write access to the fleet.
     */

    private final DeviceService deviceService;
    private final DigitalTwinService digitalTwinService;
    private final FirmwareService firmwareService;

    @Operation(summary = "Register Device", description = "Register a new IoT weather station device")
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Device registered successfully"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Invalid registration payload"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "Device UID already exists")
    })
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping
    public ResponseEntity<ApiResponse<DeviceResponse>> registerDevice(@Valid @RequestBody DeviceRegistrationRequest request) {
        log.info("POST /api/devices — Registering device UID: {}", request.getDeviceUid());
        DeviceResponse device = deviceService.registerDevice(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<DeviceResponse>builder()
                .success(true)
                .message("Device registered successfully")
                .data(device)
                .path("/api/devices")
                .build());
    }

    @Operation(summary = "Get All Devices", description = "Retrieve a list of all registered devices")
    @GetMapping
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getAllDevices() {
        log.info("GET /api/devices — Fetching all devices");
        List<DeviceResponse> devices = deviceService.getAllDevices();
        return ResponseEntity.ok(ApiResponse.<List<DeviceResponse>>builder()
                .success(true)
                .message("Devices retrieved successfully")
                .data(devices)
                .path("/api/devices")
                .build());
    }

    @Operation(summary = "Get Online Devices", description = "Retrieve a list of all online devices")
    @GetMapping("/online")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getOnlineDevices() {
        log.info("GET /api/devices/online — Fetching online devices");
        List<DeviceResponse> devices = deviceService.getDevicesByStatus(DeviceStatus.ONLINE);
        return ResponseEntity.ok(ApiResponse.<List<DeviceResponse>>builder()
                .success(true)
                .message("Online devices retrieved successfully")
                .data(devices)
                .path("/api/devices/online")
                .build());
    }

    @Operation(summary = "Get Offline Devices", description = "Retrieve a list of all offline devices")
    @GetMapping("/offline")
    public ResponseEntity<ApiResponse<List<DeviceResponse>>> getOfflineDevices() {
        log.info("GET /api/devices/offline — Fetching offline devices");
        List<DeviceResponse> devices = deviceService.getDevicesByStatus(DeviceStatus.OFFLINE);
        return ResponseEntity.ok(ApiResponse.<List<DeviceResponse>>builder()
                .success(true)
                .message("Offline devices retrieved successfully")
                .data(devices)
                .path("/api/devices/offline")
                .build());
    }

    @Operation(summary = "Get Device by UID", description = "Retrieve a single device by its unique identifier")
    @GetMapping("/{deviceUid}")
    public ResponseEntity<ApiResponse<DeviceResponse>> getDeviceByUid(
            @Parameter(description = "Unique device identifier", example = "ESP32-001")
            @PathVariable String deviceUid) {
        log.info("GET /api/devices/{}", deviceUid);
        DeviceResponse device = deviceService.getDeviceByUid(deviceUid);
        return ResponseEntity.ok(ApiResponse.<DeviceResponse>builder()
                .success(true)
                .message("Device retrieved successfully")
                .data(device)
                .path("/api/devices/" + deviceUid)
                .build());
    }

    @Operation(summary = "Update Device", description = "Update device details such as name, location, and coordinates")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{deviceUid}")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateDevice(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @Valid @RequestBody DeviceUpdateRequest request) {
        log.info("PUT /api/devices/{}", deviceUid);
        DeviceResponse device = deviceService.updateDevice(deviceUid, request);
        return ResponseEntity.ok(ApiResponse.<DeviceResponse>builder()
                .success(true)
                .message("Device updated successfully")
                .data(device)
                .path("/api/devices/" + deviceUid)
                .build());
    }

    @Operation(summary = "Delete Device", description = "Remove a registered device from the system")
    @PreAuthorize("hasRole('ADMIN')")
    @DeleteMapping("/{deviceUid}")
    public ResponseEntity<ApiResponse<Void>> deleteDevice(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid) {
        log.info("DELETE /api/devices/{}", deviceUid);
        deviceService.deleteDevice(deviceUid);
        return ResponseEntity.ok(ApiResponse.<Void>builder()
                .success(true)
                .message("Device deleted successfully")
                .path("/api/devices/" + deviceUid)
                .build());
    }

    @Operation(summary = "Update Device Status", description = "Change the lifecycle status of a device")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{deviceUid}/status")
    public ResponseEntity<ApiResponse<DeviceResponse>> updateStatus(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @Valid @RequestBody DeviceStatusUpdateRequest request) {
        log.info("PUT /api/devices/{}/status — New status: {}", deviceUid, request.getStatus());
        DeviceResponse device = deviceService.updateStatus(deviceUid, request);
        return ResponseEntity.ok(ApiResponse.<DeviceResponse>builder()
                .success(true)
                .message("Device status updated successfully")
                .data(device)
                .path("/api/devices/" + deviceUid + "/status")
                .build());
    }

    @Operation(summary = "Get Device Health", description = "Check the health and connectivity status of a device")
    @GetMapping("/{deviceUid}/health")
    public ResponseEntity<ApiResponse<DeviceHealthResponse>> getDeviceHealth(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid) {
        log.info("GET /api/devices/{}/health", deviceUid);
        DeviceHealthResponse health = deviceService.getDeviceHealth(deviceUid);
        return ResponseEntity.ok(ApiResponse.<DeviceHealthResponse>builder()
                .success(true)
                .message("Device health retrieved successfully")
                .data(health)
                .path("/api/devices/" + deviceUid + "/health")
                .build());
    }



    @Operation(summary = "Get Device Location", description = "Get GPS location coordinates and location name of an IoT station")
    @GetMapping("/{deviceUid}/location")
    public ResponseEntity<ApiResponse<DeviceLocationResponse>> getLocation(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid) {
        log.info("GET /api/devices/{}/location", deviceUid);
        DeviceLocationResponse location = deviceService.getLocation(deviceUid);
        return ResponseEntity.ok(ApiResponse.<DeviceLocationResponse>builder()
                .success(true)
                .message("Device location retrieved successfully")
                .data(location)
                .path("/api/devices/" + deviceUid + "/location")
                .build());
    }

    @Operation(summary = "Update Device Location", description = "Update GPS location coordinates and location name")
    @PreAuthorize("hasRole('ADMIN')")
    @PutMapping("/{deviceUid}/location")
    public ResponseEntity<ApiResponse<DeviceLocationResponse>> updateLocation(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @Valid @RequestBody DeviceLocationUpdateRequest request) {
        log.info("PUT /api/devices/{}/location", deviceUid);
        DeviceLocationResponse location = deviceService.updateLocation(deviceUid, request);
        return ResponseEntity.ok(ApiResponse.<DeviceLocationResponse>builder()
                .success(true)
                .message("Device location updated successfully")
                .data(location)
                .path("/api/devices/" + deviceUid + "/location")
                .build());
    }

    @Operation(summary = "Get Digital Twin", description = "Get unified real-time Digital Twin state of an IoT weather station")
    @GetMapping("/{deviceUid}/digital-twin")
    public ResponseEntity<ApiResponse<DigitalTwinResponse>> getDigitalTwin(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid) {
        log.info("GET /api/devices/{}/digital-twin", deviceUid);
        DigitalTwinResponse twin = digitalTwinService.getDigitalTwin(deviceUid);
        return ResponseEntity.ok(ApiResponse.<DigitalTwinResponse>builder()
                .success(true)
                .message("Digital twin retrieved successfully")
                .data(twin)
                .path("/api/devices/" + deviceUid + "/digital-twin")
                .build());
    }

    @Operation(summary = "Request OTA Firmware Update", description = "Trigger an OTA firmware update request for a device")
    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{deviceUid}/firmware-update")
    public ResponseEntity<ApiResponse<DeviceFirmwareUpdateResponse>> requestFirmwareUpdate(
            @PathVariable String deviceUid,
            @Valid @RequestBody FirmwareUpdateRequest request) {
        log.info("POST /api/devices/{}/firmware-update to version {}", deviceUid, request.getFirmwareVersion());
        DeviceFirmwareUpdateResponse response = firmwareService.requestUpdate(deviceUid, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.<DeviceFirmwareUpdateResponse>builder()
                .success(true)
                .message("Firmware update requested successfully")
                .data(response)
                .path("/api/devices/" + deviceUid + "/firmware-update")
                .build());
    }

    @Operation(summary = "Update OTA Firmware Progress", description = "Update the progress/status of an OTA update (DOWNLOADING, INSTALLING, SUCCESS, FAILED)")
    @PreAuthorize("hasRole('ADMIN')")
    @PatchMapping("/{deviceUid}/firmware-update/{updateId}/status")
    public ResponseEntity<ApiResponse<DeviceFirmwareUpdateResponse>> updateFirmwareStatus(
            @PathVariable String deviceUid,
            @PathVariable Long updateId,
            @Valid @RequestBody FirmwareUpdateStatusRequest request) {
        log.info("PATCH /api/devices/{}/firmware-update/{}/status -> {}", deviceUid, updateId, request.getStatus());
        DeviceFirmwareUpdateResponse response = firmwareService.updateStatus(deviceUid, updateId, request);
        return ResponseEntity.ok(ApiResponse.<DeviceFirmwareUpdateResponse>builder()
                .success(true)
                .message("Firmware update status updated")
                .data(response)
                .path("/api/devices/" + deviceUid + "/firmware-update/" + updateId + "/status")
                .build());
    }

    @Operation(summary = "Get Firmware Update History", description = "Get OTA firmware update history for a device")
    @GetMapping("/{deviceUid}/firmware-history")
    public ResponseEntity<ApiResponse<List<DeviceFirmwareUpdateResponse>>> getFirmwareHistory(
            @PathVariable String deviceUid) {
        log.info("GET /api/devices/{}/firmware-history", deviceUid);
        List<DeviceFirmwareUpdateResponse> history = firmwareService.getFirmwareHistory(deviceUid);
        return ResponseEntity.ok(ApiResponse.<List<DeviceFirmwareUpdateResponse>>builder()
                .success(true)
                .message("Firmware update history retrieved successfully")
                .data(history)
                .path("/api/devices/" + deviceUid + "/firmware-history")
                .build());
    }
}
