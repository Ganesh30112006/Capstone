package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.dto.PublicReadingResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The public portal's one-request-per-page read of the whole network. */
@ExtendWith(MockitoExtension.class)
class PublicLatestReadingsTest {

    @Mock
    private SensorDataRepository repository;

    @InjectMocks
    private PublicReadingService service;

    private static SensorData row(String uid) {
        return SensorData.builder()
                .deviceUid(uid)
                .temperature(new BigDecimal("30.41"))
                .rawTemperature(new BigDecimal("32.95"))
                .humidity(new BigDecimal("64.00"))
                .airQuality(40)
                .pressure(new BigDecimal("1011.00"))
                .rain(false)
                .recordedAt(LocalDateTime.of(2026, 10, 10, 13, 30))
                .build();
    }

    @Test
    void returnsEachStationsNewestReadingKeyedById() {
        when(repository.findLatestForEach(anyCollection())).thenReturn(List.of(row("ESP32-CHN-001"), row("ESP32-CHN-002")));

        Map<String, PublicReadingResponse> latest = service.getLatestForEach(List.of("ESP32-CHN-001", "ESP32-CHN-002"));

        assertThat(latest).containsOnlyKeys("ESP32-CHN-001", "ESP32-CHN-002");
        // The public figure is the corrected one; raw values stay internal.
        assertThat(latest.get("ESP32-CHN-001").getTemperature()).isEqualByComparingTo("30.41");
    }

    @Test
    void leavesAStationThatHasNeverReportedOutRatherThanEmpty() {
        when(repository.findLatestForEach(anyCollection())).thenReturn(List.of(row("ESP32-CHN-001")));

        assertThat(service.getLatestForEach(List.of("ESP32-CHN-001", "ESP32-CHN-999")))
                .containsOnlyKeys("ESP32-CHN-001");
    }

    @Test
    void asksOnceForEachDistinctNonBlankId() {
        when(repository.findLatestForEach(anyCollection())).thenReturn(List.of());

        service.getLatestForEach(Arrays.asList(" ESP32-CHN-001", "ESP32-CHN-001", "", null));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> asked = ArgumentCaptor.forClass(Collection.class);
        verify(repository).findLatestForEach(asked.capture());
        assertThat(asked.getValue()).containsExactly("ESP32-CHN-001");
    }

    @Test
    void doesNotQueryForNothing() {
        assertThat(service.getLatestForEach(List.of(" "))).isEmpty();
        verify(repository, never()).findLatestForEach(anyCollection());
    }
}
