package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.mapper.SensorDataMapper;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Rendering the fleet used to cost one request per station.
 *
 * `GET /devices` followed by `GET /sensors/{uid}/latest` N times meant a
 * 500-station deployment issued 501 round trips through the gateway to draw a
 * single page -- and it did so on the homepage, the map, the directory, the
 * monitor, the forecast page and the fleet table. This collapses that to one
 * query, and these tests pin the two properties that make it safe to expose:
 * a bounded request, and an absent device staying absent rather than becoming
 * a zeroed reading.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BatchLatestReadingTest {

    @Mock
    private SensorDataRepository sensorDataRepository;
    @Mock
    private SensorDataMapper sensorDataMapper;

    @InjectMocks
    private SensorDataServiceImpl service;

    private static SensorData row(String uid) {
        SensorData data = new SensorData();
        data.setDeviceUid(uid);
        data.setTemperature(BigDecimal.valueOf(21.5));
        return data;
    }

    private void mapperEchoes() {
        when(sensorDataMapper.toResponse(any(SensorData.class))).thenAnswer(invocation -> {
            SensorData source = invocation.getArgument(0);
            return SensorDataResponse.builder()
                    .deviceUid(source.getDeviceUid())
                    .temperature(source.getTemperature())
                    .build();
        });
    }

    @Test
    @DisplayName("returns one entry per device that has reported, keyed by UID")
    void keysReadingsByDeviceUid() {
        mapperEchoes();
        when(sensorDataRepository.findLatestForEach(any()))
                .thenReturn(List.of(row("ESP32-001"), row("ESP32-002")));

        Map<String, SensorDataResponse> latest = service.getLatestForEach(List.of("ESP32-001", "ESP32-002"));

        assertThat(latest).containsOnlyKeys("ESP32-001", "ESP32-002");
        assertThat(latest.get("ESP32-001").getTemperature()).isEqualByComparingTo("21.5");
    }

    @Test
    @DisplayName("a device that has never reported is absent, not a zeroed reading")
    void omitsDevicesWithNoReading() {
        mapperEchoes();
        // Two asked for, only one has telemetry.
        when(sensorDataRepository.findLatestForEach(any())).thenReturn(List.of(row("ESP32-001")));

        Map<String, SensorDataResponse> latest = service.getLatestForEach(List.of("ESP32-001", "ESP32-002"));

        assertThat(latest).containsOnlyKeys("ESP32-001");
        // The caller must be able to tell "has not reported" from "reported 0".
        assertThat(latest).doesNotContainKey("ESP32-002");
    }

    @Test
    @DisplayName("queries once for the whole batch")
    void issuesASingleQuery() {
        mapperEchoes();
        when(sensorDataRepository.findLatestForEach(any())).thenReturn(List.of());

        service.getLatestForEach(List.of("A", "B", "C", "D"));

        verify(sensorDataRepository).findLatestForEach(any());
        verify(sensorDataRepository, never()).findTopByDeviceUidOrderByRecordedAtDesc(any());
    }

    @Test
    @DisplayName("de-duplicates and trims the requested UIDs before querying")
    void normalisesTheRequestedUids() {
        when(sensorDataRepository.findLatestForEach(any())).thenReturn(List.of());

        service.getLatestForEach(List.of("ESP32-001", " ESP32-001 ", "", "   ", "ESP32-002"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(sensorDataRepository).findLatestForEach(captor.capture());
        assertThat(captor.getValue()).containsExactly("ESP32-001", "ESP32-002");
    }

    @Test
    @DisplayName("an empty or all-blank request touches the database at all")
    void skipsTheQueryWhenNothingWasAsked() {
        assertThat(service.getLatestForEach(List.of())).isEmpty();
        assertThat(service.getLatestForEach(null)).isEmpty();
        assertThat(service.getLatestForEach(List.of("  ", ""))).isEmpty();

        verify(sensorDataRepository, never()).findLatestForEach(any());
    }

    @Test
    @DisplayName("refuses an unbounded batch rather than building a huge IN list")
    void capsTheBatchSize() {
        List<String> tooMany = IntStream.rangeClosed(1, 501)
                .mapToObj(i -> "ESP32-" + i)
                .collect(Collectors.toList());

        // The UID list arrives from the caller through the public gateway, so
        // the size has to be bounded somewhere that the caller cannot change.
        assertThatThrownBy(() -> service.getLatestForEach(tooMany))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("500");

        verify(sensorDataRepository, never()).findLatestForEach(any());
    }
}
