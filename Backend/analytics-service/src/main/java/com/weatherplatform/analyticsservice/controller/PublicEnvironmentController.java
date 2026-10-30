package com.weatherplatform.analyticsservice.controller;

import com.weatherplatform.analyticsservice.dto.ApiResponse;
import com.weatherplatform.analyticsservice.dto.HeatmapResponse;
import com.weatherplatform.analyticsservice.dto.NetworkTrendResponse;
import com.weatherplatform.analyticsservice.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.List;
import java.util.Set;

/**
 * Unauthenticated environmental context for the public portal: the network
 * trend chart and the public heatmap.
 *
 * These are aggregates across the fleet, so they carry no per-device
 * operational detail. The parameter is validated against an allow-list rather
 * than passed through, so a caller cannot probe for fields that are not meant
 * to be public.
 */
@Slf4j
@RestController
@RequestMapping("/api/public/environment")
@RequiredArgsConstructor
@Tag(name = "Public Environment", description = "Unauthenticated environmental aggregates for the public portal")
public class PublicEnvironmentController {

    /**
     * Parameters a member of the public may render. An allow-list, not a
     * denylist, so adding an internal metric never leaks by default.
     */
    private static final Set<String> PUBLIC_PARAMETERS =
            Set.of("temperature", "humidity", "airQuality", "oxygen", "pressure");

    private static final CacheControl CACHE = CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic();

    private final AnalyticsService analyticsService;

    @Operation(summary = "Public Network Trend", description = "Fleet-average environmental trend")
    @GetMapping("/trend")
    public ResponseEntity<ApiResponse<List<NetworkTrendResponse>>> trend() {
        List<NetworkTrendResponse> trend = analyticsService.getNetworkTrend();
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(ApiResponse.<List<NetworkTrendResponse>>builder()
                        .success(true)
                        .message("Public network trend retrieved successfully")
                        .data(trend)
                        .path("/api/public/environment/trend")
                        .build());
    }

    @Operation(summary = "Public Heatmap", description = "Geographic distribution of a public environmental parameter")
    @GetMapping("/heatmap")
    public ResponseEntity<ApiResponse<HeatmapResponse>> heatmap(
            @Parameter(description = "One of: temperature, humidity, airQuality, oxygen, pressure")
            @RequestParam(defaultValue = "temperature") String parameter) {

        if (!PUBLIC_PARAMETERS.contains(parameter)) {
            throw new IllegalArgumentException(
                    "Unsupported parameter '" + parameter + "'. Expected one of: " + PUBLIC_PARAMETERS);
        }

        // No radius filter is exposed publicly: an anonymous caller should not be
        // able to sweep the map probing for exact station coordinates.
        HeatmapResponse response = analyticsService.getHeatmap(parameter, null, null, null);
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(ApiResponse.<HeatmapResponse>builder()
                        .success(true)
                        .message("Public heatmap retrieved successfully")
                        .data(response)
                        .path("/api/public/environment/heatmap")
                        .build());
    }
}
