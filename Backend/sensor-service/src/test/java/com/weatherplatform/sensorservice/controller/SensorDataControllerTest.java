package com.weatherplatform.sensorservice.controller;

import com.weatherplatform.sensorservice.dto.PagedSensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.exception.ResourceNotFoundException;
import com.weatherplatform.sensorservice.security.GatewayAuthenticationFilter;
import com.weatherplatform.sensorservice.security.SecurityConfig;
import com.weatherplatform.sensorservice.service.SensorDataService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SensorDataController.class)
@Import({SecurityConfig.class, GatewayAuthenticationFilter.class})
class SensorDataControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorDataService sensorDataService;

    private SensorDataResponse sampleResponse;

    /**
     * Mimics the headers the API Gateway attaches after it has verified the JWT.
     * The service itself never sees a token — this is the real production shape.
     */
    private static MockHttpServletRequestBuilder asUser(MockHttpServletRequestBuilder request) {
        return request
                .header(GatewayAuthenticationFilter.HEADER_USER_ID, "42")
                .header(GatewayAuthenticationFilter.HEADER_USER_EMAIL, "operator@example.com")
                .header(GatewayAuthenticationFilter.HEADER_USER_ROLE, "USER");
    }

    @BeforeEach
    void setUp() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 6, 10, 0, 0);

        sampleResponse = SensorDataResponse.builder()
                .id(100L)
                .deviceUid("ESP32-CHN-001")
                .temperature(new BigDecimal("32.50"))
                .humidity(new BigDecimal("68.00"))
                .airQuality(110)
                .pressure(new BigDecimal("1009.50"))
                .rain(false)
                .recordedAt(now)
                .createdAt(now)
                .build();
    }

    @Test
    @DisplayName("GET latest without gateway identity - 401 Unauthorized")
    void getLatest_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/sensors/ESP32-CHN-001/latest"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET history without gateway identity - 401 Unauthorized")
    void getHistory_Unauthenticated_Returns401() throws Exception {
        mockMvc.perform(get("/api/sensors/ESP32-CHN-001/history"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("GET /api/sensors/{deviceUid}/latest - 200 OK, wrapped in ApiResponse")
    void getLatest_Found_Returns200() throws Exception {
        when(sensorDataService.getLatestByDeviceUid("ESP32-CHN-001")).thenReturn(sampleResponse);

        mockMvc.perform(asUser(get("/api/sensors/ESP32-CHN-001/latest")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.deviceUid").value("ESP32-CHN-001"))
                .andExpect(jsonPath("$.data.id").value(100))
                .andExpect(jsonPath("$.data.airQuality").value(110));
    }

    @Test
    @DisplayName("GET /api/sensors/{deviceUid}/latest - 404 Not Found")
    void getLatest_NotFound_Returns404() throws Exception {
        when(sensorDataService.getLatestByDeviceUid("UNKNOWN-DEVICE"))
                .thenThrow(new ResourceNotFoundException("No sensor data found for device: UNKNOWN-DEVICE"));

        mockMvc.perform(asUser(get("/api/sensors/UNKNOWN-DEVICE/latest")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.message").value("No sensor data found for device: UNKNOWN-DEVICE"));
    }

    @Test
    @DisplayName("GET /api/sensors/{deviceUid}/history - 200 OK with paged payload")
    void getHistory_Returns200() throws Exception {
        PagedSensorDataResponse pagedResponse = PagedSensorDataResponse.builder()
                .content(List.of(sampleResponse))
                .pageNumber(0)
                .pageSize(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();

        when(sensorDataService.getHistoryByDeviceUid("ESP32-CHN-001", 0, 20)).thenReturn(pagedResponse);

        mockMvc.perform(asUser(get("/api/sensors/ESP32-CHN-001/history")
                        .param("page", "0")
                        .param("size", "20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.content[0].deviceUid").value("ESP32-CHN-001"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    @DisplayName("GET /api/sensors/{deviceUid}/history?from=... - delegates to date-range query")
    void getHistory_WithDateRange_Returns200() throws Exception {
        PagedSensorDataResponse pagedResponse = PagedSensorDataResponse.builder()
                .content(List.of(sampleResponse))
                .pageNumber(0)
                .pageSize(20)
                .totalElements(1)
                .totalPages(1)
                .last(true)
                .build();

        when(sensorDataService.getHistoryByDeviceUidBetweenDates(
                eq("ESP32-CHN-001"), any(LocalDateTime.class), any(LocalDateTime.class), eq(0), eq(20)))
                .thenReturn(pagedResponse);

        mockMvc.perform(asUser(get("/api/sensors/ESP32-CHN-001/history")
                        .param("from", "2026-08-01T00:00:00")
                        .param("to", "2026-08-06T00:00:00")
                        .param("page", "0")
                        .param("size", "20")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].deviceUid").value("ESP32-CHN-001"));
    }

    /**
     * GET /api/sensors/health requires {@code deviceUids}, and calling it
     * without one -- the shape a malformed client request or a typo in a query
     * string produces -- fell through to the catch-all handler and came back
     * as a 500. A caller mistake reported as an internal server error tells a
     * retrying client to try again against a request that can never succeed,
     * and this platform's own retry policy retries a 500 once, silently
     * masking the real problem behind a wasted request.
     */
    @Test
    @DisplayName("GET /api/sensors/health without deviceUids is a 400, not a 500")
    void getHealthForEach_WithoutDeviceUids_Returns400NotInternalServerError() throws Exception {
        mockMvc.perform(asUser(get("/api/sensors/health")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"))
                .andExpect(jsonPath("$.message").value("Missing required parameter: deviceUids"));
    }

    @Test
    @DisplayName("GET /api/sensors/{deviceUid}/history with a non-numeric page is a 400, not a 500")
    void getHistory_WithNonNumericPage_Returns400NotInternalServerError() throws Exception {
        mockMvc.perform(asUser(get("/api/sensors/ESP32-CHN-001/history")
                        .param("from", "2026-08-01T00:00:00")
                        .param("to", "2026-08-06T00:00:00")
                        .param("page", "not-a-number")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Bad Request"));
    }
}
