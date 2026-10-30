package com.weatherplatform.deviceservice.controller;

import com.weatherplatform.deviceservice.dto.ApiResponse;
import com.weatherplatform.deviceservice.dto.DeviceCommandRequest;
import com.weatherplatform.deviceservice.dto.DeviceCommandResponse;
import com.weatherplatform.deviceservice.service.DeviceCommandService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;

/**
 * The only supported way to instruct a physical station.
 *
 * Replaces the previous browser-side hardware console. The command is an enum,
 * not a string, so the request body cannot smuggle an arbitrary instruction;
 * an unknown value fails JSON binding and returns 400 before any code runs.
 */
@Slf4j
@RestController
@RequestMapping("/api/devices/{deviceUid}/commands")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
@Tag(name = "Device Commands", description = "Authorized operations dispatched via AWS IoT Core")
public class DeviceCommandController {

    private final DeviceCommandService deviceCommandService;

    @Operation(summary = "Send Device Command",
            description = "Publishes one of the predefined operations to the station over AWS IoT Core")
    @PostMapping
    public ResponseEntity<ApiResponse<DeviceCommandResponse>> send(
            @PathVariable String deviceUid,
            @Valid @RequestBody DeviceCommandRequest request,
            Authentication authentication) {

        String issuedBy = authentication != null ? String.valueOf(authentication.getPrincipal()) : "unknown";
        log.info("POST /api/devices/{}/commands — {} by {}", deviceUid, request.getCommand(), issuedBy);

        DeviceCommandResponse response = deviceCommandService.send(deviceUid, request.getCommand(), issuedBy);

        return ResponseEntity.ok(ApiResponse.<DeviceCommandResponse>builder()
                .success(true)
                // Deliberately precise: published to the broker, not executed by the device.
                .message("Command published to the device. Execution is confirmed by the station's next report.")
                .data(response)
                .path("/api/devices/" + deviceUid + "/commands")
                .build());
    }

    /** Lets the console render exactly the operations the backend supports. */
    @Operation(summary = "List Supported Commands", description = "The complete set of permitted device operations")
    @org.springframework.web.bind.annotation.GetMapping
    public ResponseEntity<ApiResponse<List<String>>> supportedCommands(@PathVariable String deviceUid) {
        List<String> commands = Arrays.stream(com.weatherplatform.deviceservice.entity.DeviceCommand.values())
                .map(Enum::name)
                .toList();

        return ResponseEntity.ok(ApiResponse.<List<String>>builder()
                .success(true)
                .message("Supported commands retrieved")
                .data(commands)
                .path("/api/devices/" + deviceUid + "/commands")
                .build());
    }
}
