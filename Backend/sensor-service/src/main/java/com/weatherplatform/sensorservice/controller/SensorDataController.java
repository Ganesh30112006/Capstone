package com.weatherplatform.sensorservice.controller;

import com.weatherplatform.sensorservice.dto.ApiResponse;
import com.weatherplatform.sensorservice.dto.PagedSensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.dto.SensorStatisticsResponse;
import com.weatherplatform.sensorservice.service.SensorDataService;
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

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Slf4j
@RestController
@RequestMapping("/api/sensors")
@RequiredArgsConstructor
@Tag(name = "Sensor Data", description = "Read-only queries over stored environmental sensor measurements")
public class SensorDataController {

    private final SensorDataService sensorDataService;

    @Operation(summary = "Get Latest Reading", description = "Retrieve the most recent sensor measurement for a device")
    @GetMapping("/{deviceUid}/latest")
    public ResponseEntity<ApiResponse<SensorDataResponse>> getLatest(
            @Parameter(description = "Unique device identifier", example = "ESP32-001")
            @PathVariable String deviceUid) {
        log.info("GET /api/sensors/{}/latest", deviceUid);
        SensorDataResponse response = sensorDataService.getLatestByDeviceUid(deviceUid);
        return ResponseEntity.ok(ApiResponse.<SensorDataResponse>builder()
                .success(true)
                .message("Latest reading retrieved successfully")
                .data(response)
                .path("/api/sensors/" + deviceUid + "/latest")
                .build());
    }

    @Operation(
            summary = "Get Latest Readings For Several Devices",
            description = "Most recent measurement for each requested device, keyed by device UID. "
                    + "A device that has never reported is absent from the map rather than present "
                    + "with a null or zeroed reading, so callers can tell 'has not reported' from "
                    + "'reported zero'. At most 500 UIDs per request.")
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, SensorDataResponse>>> getLatestForEach(
            @Parameter(description = "Comma-separated device UIDs", example = "ESP32-001,ESP32-002")
            @RequestParam List<String> deviceUids) {
        log.info("GET /api/sensors/latest ({} uids)", deviceUids.size());
        Map<String, SensorDataResponse> readings = sensorDataService.getLatestForEach(deviceUids);
        return ResponseEntity.ok(ApiResponse.<Map<String, SensorDataResponse>>builder()
                .success(true)
                .message("Latest readings retrieved successfully")
                .data(readings)
                .path("/api/sensors/latest")
                .build());
    }

    @Operation(summary = "Get Historical Readings", description = "Retrieve paginated historical measurements, optionally filtered by date range")
    @GetMapping("/{deviceUid}/history")
    public ResponseEntity<ApiResponse<PagedSensorDataResponse>> getHistory(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid,
            @Parameter(description = "Filter start (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @Parameter(description = "Filter end (ISO format)") @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        log.info("GET /api/sensors/{}/history (from={}, to={}, page={}, size={})", deviceUid, from, to, page, size);

        PagedSensorDataResponse response;
        if (from != null || to != null) {
            response = sensorDataService.getHistoryByDeviceUidBetweenDates(deviceUid, from, to, page, size);
        } else {
            response = sensorDataService.getHistoryByDeviceUid(deviceUid, page, size);
        }

        return ResponseEntity.ok(ApiResponse.<PagedSensorDataResponse>builder()
                .success(true)
                .message("History retrieved successfully")
                .data(response)
                .path("/api/sensors/" + deviceUid + "/history")
                .build());
    }

    @Operation(summary = "Get Sensor Statistics", description = "Get aggregate statistics (min/max/avg) for all sensor types of a device")
    @GetMapping("/{deviceUid}/statistics")
    public ResponseEntity<ApiResponse<SensorStatisticsResponse>> getStatistics(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid) {
        log.info("GET /api/sensors/{}/statistics", deviceUid);
        SensorStatisticsResponse response = sensorDataService.getStatisticsByDeviceUid(deviceUid);
        return ResponseEntity.ok(ApiResponse.<SensorStatisticsResponse>builder()
                .success(true)
                .message("Statistics retrieved successfully")
                .data(response)
                .path("/api/sensors/" + deviceUid + "/statistics")
                .build());
    }

    @Operation(
            summary = "Get Sensor Health For Several Devices",
            description = "Sensor diagnostics for each requested device, keyed by device UID. "
                    + "A device with no diagnostics on file is absent from the map rather than "
                    + "present with an empty, healthy-looking report, so callers can tell "
                    + "'never evaluated' from 'every probe passed'. At most 500 UIDs per request.")
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<Map<String, SensorHealthResponse>>> getSensorHealthForEach(
            @Parameter(description = "Comma-separated device UIDs", example = "ESP32-001,ESP32-002")
            @RequestParam List<String> deviceUids) {
        log.info("GET /api/sensors/health ({} uids)", deviceUids.size());
        Map<String, SensorHealthResponse> reports = sensorDataService.getSensorHealthForEach(deviceUids);
        return ResponseEntity.ok(ApiResponse.<Map<String, SensorHealthResponse>>builder()
                .success(true)
                .message("Sensor health retrieved successfully")
                .data(reports)
                .path("/api/sensors/health")
                .build());
    }

    @Operation(summary = "Get Sensor Health", description = "Get diagnostic health status (HEALTHY, WARNING, FAILED, or UNKNOWN when nothing has been evaluated) for all sensors on a device")
    @GetMapping({"/device/{deviceUid}/health", "/{deviceUid}/health"})
    public ResponseEntity<ApiResponse<SensorHealthResponse>> getSensorHealth(
            @Parameter(description = "Unique device identifier") @PathVariable String deviceUid) {
        log.info("GET /api/sensors/device/{}/health", deviceUid);
        SensorHealthResponse response = sensorDataService.getSensorHealth(deviceUid);
        return ResponseEntity.ok(ApiResponse.<SensorHealthResponse>builder()
                .success(true)
                .message("Sensor health retrieved successfully")
                .data(response)
                .path("/api/sensors/device/" + deviceUid + "/health")
                .build());
    }
}
