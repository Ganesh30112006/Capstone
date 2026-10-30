package com.weatherplatform.deviceservice.controller;

import com.weatherplatform.deviceservice.dto.ApiResponse;
import com.weatherplatform.deviceservice.dto.PublicStationResponse;
import com.weatherplatform.deviceservice.service.PublicStationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;

/**
 * Unauthenticated station directory for the public portal.
 *
 * Only stations explicitly flagged public are returned, and only through the
 * sanitised {@link PublicStationResponse} projection.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/stations")
@RequiredArgsConstructor
@Tag(name = "Public Stations", description = "Unauthenticated station directory for the public portal")
public class PublicStationController {

    private final PublicStationService publicStationService;

    /** Public reads are cacheable — they change at most once per reporting interval. */
    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic();

    @Operation(summary = "List Public Stations", description = "Stations published to the public portal")
    @GetMapping
    public ResponseEntity<ApiResponse<List<PublicStationResponse>>> listStations() {
        List<PublicStationResponse> stations = publicStationService.listPublicStations();
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(ApiResponse.<List<PublicStationResponse>>builder()
                        .success(true)
                        .message("Public stations retrieved successfully")
                        .data(stations)
                        .path("/api/public/stations")
                        .build());
    }

    @Operation(summary = "Get Public Station", description = "A single publicly visible station")
    @GetMapping("/{stationId}")
    public ResponseEntity<ApiResponse<PublicStationResponse>> getStation(@PathVariable String stationId) {
        PublicStationResponse station = publicStationService.getPublicStation(stationId);
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(ApiResponse.<PublicStationResponse>builder()
                        .success(true)
                        .message("Public station retrieved successfully")
                        .data(station)
                        .path("/api/public/stations/" + stationId)
                        .build());
    }
}
