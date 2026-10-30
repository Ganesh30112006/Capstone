package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.config.RabbitMQConfig;
import com.weatherplatform.sensorservice.dto.PagedSensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.dto.SensorStatisticsResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.event.SensorReadingEvent;
import com.weatherplatform.sensorservice.exception.InvalidDateRangeException;
import com.weatherplatform.sensorservice.exception.ResourceNotFoundException;
import com.weatherplatform.sensorservice.mapper.SensorDataMapper;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.stream.Collectors;
import java.util.Set;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Collection;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SensorDataServiceImpl implements SensorDataService {

    /** Upper bound on one batch request; the endpoint is reachable from the gateway. */
    private static final int MAX_BATCH_DEVICES = 500;

    private final SensorDataRepository sensorDataRepository;
    private final SensorDataMapper sensorDataMapper;
    private final RabbitTemplate rabbitTemplate;
    private final SensorFailureDetector failureDetector;
    private final com.weatherplatform.sensorservice.calibration.CalibrationService calibrationService;
    private final com.weatherplatform.sensorservice.calibration.SensorStatusService sensorStatusService;

    @Override
    @Transactional
    public SensorDataResponse storeSensorData(SensorDataRequest request) {
        log.info("Storing new sensor measurement for deviceUid: {}", request.getDeviceUid());

        // 0. Idempotency. A redelivered SQS message or a RabbitMQ retry must not
        //    create a second reading, which would skew every downstream average.
        //    The cheap pre-check avoids the common case; the unique constraint on
        //    message_id is what actually guarantees it under concurrency.
        if (request.getMessageId() != null && !request.getMessageId().isBlank()
                && sensorDataRepository.existsByMessageId(request.getMessageId())) {
            log.info("Telemetry {} already stored for device {} — skipping duplicate",
                    request.getMessageId(), request.getDeviceUid());
            return sensorDataRepository.findByMessageId(request.getMessageId())
                    .map(sensorDataMapper::toResponse)
                    .orElseThrow(() -> new ResourceNotFoundException(
                            "Reading vanished between duplicate check and read: " + request.getMessageId()));
        }

        // 1. Evaluate sensor failure health statuses against the recent history.
        Map<String, String> healthStatuses = failureDetector.evaluateAndRecordHealth(request);

        // 2. Correct the reading with the calibration in force when it was taken,
        //    keeping the sensor's raw values beside the corrected ones. Health was
        //    judged above on the raw values; everything downstream -- storage,
        //    analytics, alerts, the AI worker -- gets the corrected ones.
        SensorData entity = sensorDataMapper.toEntity(request);
        calibrationService.applyCorrections(entity);

        // 3. Persist the reading.
        SensorData saved;
        try {
            saved = sensorDataRepository.saveAndFlush(entity);
        } catch (DataIntegrityViolationException e) {
            // Lost a race with a concurrent delivery of the same message. The other
            // writer already published the event, so this delivery is complete.
            log.info("Concurrent duplicate of telemetry {} for device {} — treating as processed",
                    request.getMessageId(), request.getDeviceUid());
            return sensorDataRepository.findByMessageId(request.getMessageId())
                    .map(sensorDataMapper::toResponse)
                    .orElseThrow(() -> e);
        }
        log.debug("Persisted sensor data with ID: {}", saved.getId());

        // 4. Publish the enriched reading for analytics, alerting, the digital
        //    twin and the AI worker. AI consumes this same event on its own queue,
        //    so inference can never block or fail telemetry storage.
        publishSensorReadingEvent(saved, healthStatuses);

        return sensorDataMapper.toResponse(saved);
    }

    @Override
    public SensorDataResponse getLatestByDeviceUid(String deviceUid) {
        log.info("Fetching latest sensor measurement for deviceUid: {}", deviceUid);
        SensorData latest = sensorDataRepository.findTopByDeviceUidOrderByRecordedAtDesc(deviceUid)
                .orElseThrow(() -> {
                    log.warn("No telemetry records found for deviceUid: {}", deviceUid);
                    return new ResourceNotFoundException("No sensor data found for device: " + deviceUid);
                });
        return sensorDataMapper.toResponse(latest);
    }

    /**
     * One query for the whole fleet instead of one per station.
     *
     * The cap exists because this is reachable through the public gateway and
     * the UID list arrives from the caller: without it, a single request could
     * ask for an unbounded IN list. Callers with more stations than the cap
     * page through it.
     */
    @Override
    public Map<String, SensorDataResponse> getLatestForEach(Collection<String> deviceUids) {
        Set<String> unique = normaliseBatch(deviceUids);
        if (unique.isEmpty()) {
            return Map.of();
        }

        log.info("Fetching latest measurement for {} devices in one query", unique.size());
        Map<String, SensorDataResponse> latest = new LinkedHashMap<>();
        for (SensorData row : sensorDataRepository.findLatestForEach(unique)) {
            // A device with no row is absent from the map, never a zeroed entry.
            latest.put(row.getDeviceUid(), sensorDataMapper.toResponse(row));
        }
        return latest;
    }

    @Override
    public Map<String, SensorHealthResponse> getSensorHealthForEach(Collection<String> deviceUids) {
        Set<String> unique = normaliseBatch(deviceUids);
        if (unique.isEmpty()) {
            return Map.of();
        }

        log.info("Fetching sensor diagnostics for {} devices in one query", unique.size());
        return sensorStatusService.forEach(unique);
    }

    /**
     * Trims, drops blanks, removes duplicates and enforces the batch ceiling.
     *
     * The UID list arrives from the caller through the public gateway, so the
     * cap is what stops one request turning into an unbounded IN clause.
     */
    private Set<String> normaliseBatch(Collection<String> deviceUids) {
        if (deviceUids == null || deviceUids.isEmpty()) {
            return Set.of();
        }
        Set<String> unique = deviceUids.stream()
                .filter(uid -> uid != null && !uid.isBlank())
                .map(String::trim)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        if (unique.size() > MAX_BATCH_DEVICES) {
            throw new IllegalArgumentException(
                    "At most " + MAX_BATCH_DEVICES + " device UIDs may be requested at once; got " + unique.size());
        }
        return unique;
    }

    @Override
    public PagedSensorDataResponse getHistoryByDeviceUid(String deviceUid, int page, int size) {
        log.info("Fetching sensor history for deviceUid: {}, page: {}, size: {}", deviceUid, page, size);
        Page<SensorData> result = sensorDataRepository.findByDeviceUidOrderByRecordedAtDesc(
                deviceUid, PageRequest.of(page, size));
        return buildPagedResponse(result);
    }

    @Override
    public PagedSensorDataResponse getHistoryByDeviceUidBetweenDates(
            String deviceUid, LocalDateTime startDate, LocalDateTime endDate, int page, int size) {
        log.info("Fetching sensor history for deviceUid: {} between {} and {}", deviceUid, startDate, endDate);

        if (startDate != null && endDate != null && startDate.isAfter(endDate)) {
            throw new InvalidDateRangeException("startDate (" + startDate + ") cannot be after endDate (" + endDate + ")");
        }

        Page<SensorData> result;
        if (startDate != null && endDate != null) {
            result = sensorDataRepository.findByDeviceUidAndRecordedAtBetweenOrderByRecordedAtDesc(
                    deviceUid, startDate, endDate, PageRequest.of(page, size));
        } else {
            result = sensorDataRepository.findByDeviceUidOrderByRecordedAtDesc(
                    deviceUid, PageRequest.of(page, size));
        }

        return buildPagedResponse(result);
    }

    @Override
    public SensorStatisticsResponse getStatisticsByDeviceUid(String deviceUid) {
        log.info("Computing statistics for deviceUid: {}", deviceUid);
        SensorDataRepository.SensorStatisticsProjection stats =
                sensorDataRepository.getStatisticsByDeviceUid(deviceUid);

        if (stats == null || stats.getTotalReadings() == null || stats.getTotalReadings() == 0) {
            throw new ResourceNotFoundException("No sensor data found for device: " + deviceUid);
        }

        return SensorStatisticsResponse.builder()
                .deviceUid(deviceUid)
                .totalReadings(stats.getTotalReadings())
                .avgTemperature(toBigDecimal(stats.getAvgTemperature()))
                .minTemperature(stats.getMinTemperature())
                .maxTemperature(stats.getMaxTemperature())
                .avgHumidity(toBigDecimal(stats.getAvgHumidity()))
                .minHumidity(stats.getMinHumidity())
                .maxHumidity(stats.getMaxHumidity())
                .avgAirQuality(stats.getAvgAirQuality())
                .minAirQuality(stats.getMinAirQuality())
                .maxAirQuality(stats.getMaxAirQuality())
                .avgPressure(toBigDecimal(stats.getAvgPressure()))
                .minPressure(stats.getMinPressure())
                .maxPressure(stats.getMaxPressure())
                .build();
    }

    @Override
    public SensorHealthResponse getSensorHealth(String deviceUid) {
        log.info("Fetching sensor health for deviceUid: {}", deviceUid);
        return sensorStatusService.forDevice(deviceUid);
    }

    private void publishSensorReadingEvent(SensorData data, Map<String, String> healthStatuses) {
        try {
            SensorReadingEvent event = SensorReadingEvent.builder()
                    .deviceUid(data.getDeviceUid())
                    .temperature(data.getTemperature())
                    .humidity(data.getHumidity())
                    .airQuality(data.getAirQuality())
                    .pressure(data.getPressure())
                    .rain(data.getRain())
                    .oxygen(data.getOxygen())
                    .temperatureStatus(healthStatuses.getOrDefault("temperature", SensorFailureDetector.STATUS_UNKNOWN))
                    .humidityStatus(healthStatuses.getOrDefault("humidity", SensorFailureDetector.STATUS_UNKNOWN))
                    .airQualityStatus(healthStatuses.getOrDefault("airQuality", SensorFailureDetector.STATUS_UNKNOWN))
                    .pressureStatus(healthStatuses.getOrDefault("pressure", SensorFailureDetector.STATUS_UNKNOWN))
                    .rainStatus(healthStatuses.getOrDefault("rain", SensorFailureDetector.STATUS_UNKNOWN))
                    .oxygenStatus(healthStatuses.getOrDefault("oxygen", SensorFailureDetector.STATUS_UNKNOWN))
                    .latitude(data.getLatitude())
                    .longitude(data.getLongitude())
                    .recordedAt(data.getRecordedAt())
                    .build();

            rabbitTemplate.convertAndSend(
                    RabbitMQConfig.EXCHANGE_NAME,
                    RabbitMQConfig.SENSOR_READING_ROUTING_KEY,
                    event);
            log.info("Published SensorReadingCreated event for deviceUid: {}", data.getDeviceUid());
        } catch (Exception e) {
            log.error("Failed to publish SensorReadingCreated event for deviceUid: {}", data.getDeviceUid(), e);
        }
    }

    private PagedSensorDataResponse buildPagedResponse(Page<SensorData> result) {
        return PagedSensorDataResponse.builder()
                .content(result.getContent().stream().map(sensorDataMapper::toResponse).toList())
                .pageNumber(result.getNumber())
                .pageSize(result.getSize())
                .totalElements(result.getTotalElements())
                .totalPages(result.getTotalPages())
                .last(result.isLast())
                .build();
    }

    private BigDecimal toBigDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP) : null;
    }
}
