package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.dto.PublicReadingResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.exception.ResourceNotFoundException;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Read-only, sanitised telemetry for unauthenticated visitors.
 *
 * Kept apart from {@link SensorDataServiceImpl} so the public projection has a
 * single definition and cannot accidentally inherit fields added to the
 * operator-facing DTO.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PublicReadingService {

    /** Ceiling on rows returned to an anonymous caller, regardless of window. */
    private static final int MAX_PUBLIC_ROWS = 500;

    private final SensorDataRepository sensorDataRepository;

    public PublicReadingResponse getLatest(String stationId) {
        SensorData latest = sensorDataRepository.findTopByDeviceUidOrderByRecordedAtDesc(stationId)
                .orElseThrow(() -> new ResourceNotFoundException("No public readings for station: " + stationId));
        return toPublicResponse(latest);
    }

    /**
     * The newest reading of each listed station, in one query.
     *
     * A station that has never reported is absent from the map rather than
     * present with an empty reading, so "no reading yet" stays distinct from a
     * reading of zero.
     */
    public Map<String, PublicReadingResponse> getLatestForEach(Collection<String> stationIds) {
        Set<String> unique = new LinkedHashSet<>();
        for (String id : stationIds) {
            if (id != null && !id.isBlank()) unique.add(id.trim());
        }
        Map<String, PublicReadingResponse> latest = new LinkedHashMap<>();
        if (unique.isEmpty()) return latest;
        for (SensorData row : sensorDataRepository.findLatestForEach(unique)) {
            latest.put(row.getDeviceUid(), toPublicResponse(row));
        }
        return latest;
    }

    public List<PublicReadingResponse> getRecentHistory(String stationId, int hours) {
        LocalDateTime since = LocalDateTime.now(ZoneOffset.UTC).minusHours(hours);

        return sensorDataRepository
                .findByDeviceUidAndRecordedAtBetweenOrderByRecordedAtDesc(
                        stationId, since, LocalDateTime.now(ZoneOffset.UTC),
                        PageRequest.of(0, MAX_PUBLIC_ROWS))
                .getContent().stream()
                .map(this::toPublicResponse)
                .toList();
    }

    private PublicReadingResponse toPublicResponse(SensorData data) {
        return PublicReadingResponse.builder()
                .stationId(data.getDeviceUid())
                .temperature(data.getTemperature())
                .humidity(data.getHumidity())
                .airQuality(data.getAirQuality())
                .oxygen(data.getOxygen())
                .pressure(data.getPressure())
                .rain(data.getRain())
                .recordedAt(data.getRecordedAt())
                .build();
    }
}
