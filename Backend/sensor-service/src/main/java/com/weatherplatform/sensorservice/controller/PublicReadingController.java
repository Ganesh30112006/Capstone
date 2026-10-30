package com.weatherplatform.sensorservice.controller;

import com.weatherplatform.sensorservice.dto.ApiResponse;
import com.weatherplatform.sensorservice.dto.PublicReadingResponse;
import com.weatherplatform.sensorservice.service.PublicReadingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Unauthenticated environmental readings for the public portal.
 *
 * Every response is a sanitised projection, and history is hard-bounded so an
 * anonymous caller cannot ask for an unbounded scan of the largest table in
 * the platform.
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/api/public/readings")
@RequiredArgsConstructor
@Tag(name = "Public Readings", description = "Unauthenticated environmental readings for the public portal")
public class PublicReadingController {

    private final PublicReadingService publicReadingService;

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    /** Short, because the public pages re-read this every few seconds. */
    private static final CacheControl BATCH_CACHE = CacheControl.maxAge(Duration.ofSeconds(2)).cachePublic();

    @Operation(summary = "Latest Public Readings",
            description = "Most recent reading for each listed station, keyed by station id. A station that has "
                    + "never reported is absent from the map. At most 100 ids per request.")
    @GetMapping("/latest")
    public ResponseEntity<ApiResponse<Map<String, PublicReadingResponse>>> latestForEach(
            @Parameter(description = "Comma-separated station ids")
            @RequestParam
            @Size(min = 1, max = 100, message = "between 1 and 100 stationIds per request") List<String> stationIds) {
        return ResponseEntity.ok()
                .cacheControl(BATCH_CACHE)
                .body(ApiResponse.<Map<String, PublicReadingResponse>>builder()
                        .success(true)
                        .message("Latest public readings retrieved successfully")
                        .data(publicReadingService.getLatestForEach(stationIds))
                        .path("/api/public/readings/latest")
                        .build());
    }

    @Operation(summary = "Latest Public Reading", description = "Most recent reading for a publicly visible station")
    @GetMapping("/{stationId}/latest")
    public ResponseEntity<ApiResponse<PublicReadingResponse>> latest(@PathVariable String stationId) {
        PublicReadingResponse reading = publicReadingService.getLatest(stationId);
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(ApiResponse.<PublicReadingResponse>builder()
                        .success(true)
                        .message("Latest public reading retrieved successfully")
                        .data(reading)
                        .path("/api/public/readings/" + stationId + "/latest")
                        .build());
    }

    @Operation(summary = "Recent Public History",
            description = "Readings from the last N hours (maximum 168, i.e. one week)")
    @GetMapping("/{stationId}/history")
    public ResponseEntity<ApiResponse<List<PublicReadingResponse>>> history(
            @PathVariable String stationId,
            @Parameter(description = "Look-back window in hours, 1–168")
            @RequestParam(defaultValue = "24")
            @Min(value = 1, message = "hours must be at least 1")
            @Max(value = 168, message = "hours must be at most 168") int hours) {

        List<PublicReadingResponse> readings = publicReadingService.getRecentHistory(stationId, hours);
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(ApiResponse.<List<PublicReadingResponse>>builder()
                        .success(true)
                        .message("Public history retrieved successfully")
                        .data(readings)
                        .path("/api/public/readings/" + stationId + "/history")
                        .build());
    }
}
