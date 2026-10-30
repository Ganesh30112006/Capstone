package com.weatherplatform.sensorservice.calibration;

import com.weatherplatform.sensorservice.config.CalibrationProperties;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.entity.SensorCalibration;
import com.weatherplatform.sensorservice.repository.SensorCalibrationRepository;
import com.weatherplatform.sensorservice.service.SensorFailureDetector;
import com.weatherplatform.sensorservice.service.SensorHealthLedger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The full picture of each sensor: the detector's latest verdict, plus how it
 * has done over the last day and where its calibration stands.
 *
 * Four queries for any number of devices -- health records, the hourly ledger,
 * and the two newest calibrations of every sensor.
 */
@Service
@RequiredArgsConstructor
public class SensorStatusService {

    /** The window a health score covers. */
    static final Duration HEALTH_WINDOW = Duration.ofHours(24);

    private final SensorFailureDetector detector;
    private final SensorHealthLedger ledger;
    private final SensorCalibrationRepository calibrationRepository;
    private final CalibrationProperties calibrationProperties;
    private final Clock clock;

    public Map<String, SensorHealthResponse> forEach(Collection<String> deviceUids) {
        Map<String, SensorHealthResponse> base = detector.getSensorHealthForEach(deviceUids);
        if (base.isEmpty()) return base;

        LocalDateTime now = LocalDateTime.now(clock);
        // The current partial hour plus the 23 before it.
        Map<String, Map<String, SensorHealthLedger.Tally>> tallies =
                ledger.window(base.keySet(), now.minus(HEALTH_WINDOW).plusHours(1));
        Map<String, Map<String, List<SensorCalibration>>> calibrations = latestTwo(base.keySet());

        Map<String, SensorHealthResponse> enriched = new LinkedHashMap<>();
        base.forEach((uid, report) -> enriched.put(uid, enrich(report,
                tallies.getOrDefault(uid, Map.of()),
                calibrations.getOrDefault(uid, Map.of()), now)));
        return enriched;
    }

    public SensorHealthResponse forDevice(String deviceUid) {
        SensorHealthResponse report = forEach(List.of(deviceUid)).get(deviceUid);
        // A device with no health records is reported as UNKNOWN by the detector
        // rather than omitted, so the single-device view keeps that answer.
        return report != null ? report : detector.getDeviceSensorHealth(deviceUid);
    }

    private SensorHealthResponse enrich(SensorHealthResponse report,
                                        Map<String, SensorHealthLedger.Tally> tallies,
                                        Map<String, List<SensorCalibration>> calibrations,
                                        LocalDateTime now) {
        Map<String, SensorHealthResponse.SensorStatusDetail> sensors = new LinkedHashMap<>();
        report.getSensors().forEach((type, detail) -> sensors.put(type, detail.toBuilder()
                .health(score(tallies.get(type)))
                .calibration(calibrationProperties.isCalibratable(type)
                        ? summarise(calibrations.getOrDefault(type, List.of()), now)
                        : null)
                .build()));
        return SensorHealthResponse.builder()
                .deviceUid(report.getDeviceUid())
                .overallStatus(report.getOverallStatus())
                .sensors(sensors)
                .lastCheckedAt(report.getLastCheckedAt())
                .build();
    }

    /** Null with no evaluations in the window. */
    static SensorHealthResponse.HealthScore score(SensorHealthLedger.Tally tally) {
        if (tally == null || tally.total() == 0) return null;
        // Rounded down, so 2,999 passes out of 3,000 reads 99.9% rather than 100%.
        BigDecimal percent = BigDecimal.valueOf(tally.passed())
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(tally.total()), 1, RoundingMode.DOWN);
        return SensorHealthResponse.HealthScore.builder()
                .percent(percent)
                .passed(tally.passed())
                .warned(tally.warned())
                .failed(tally.failed())
                .windowStart(tally.windowStart())
                .build();
    }

    /** {@code newestFirst} holds at most the two newest calibrations of one sensor. */
    SensorHealthResponse.CalibrationSummary summarise(List<SensorCalibration> newestFirst, LocalDateTime now) {
        SensorCalibration latest = newestFirst.isEmpty() ? null : newestFirst.get(0);
        SensorCalibration previous = newestFirst.size() > 1 ? newestFirst.get(1) : null;
        CalibrationStatus status = CalibrationMath.status(latest, now, calibrationProperties.getDueSoonDays());
        if (latest == null) {
            return SensorHealthResponse.CalibrationSummary.builder().status(status).build();
        }
        BigDecimal drift = CalibrationMath.driftSinceLastCalibration(latest, previous, now);
        return SensorHealthResponse.CalibrationSummary.builder()
                .status(status)
                .lastCalibratedAt(latest.getCalibratedAt())
                .validUntil(latest.getValidUntil())
                .offset(latest.getOffsetValue())
                .driftEstimate(drift)
                .driftBaselineFrom(drift == null ? null : previous.getCalibratedAt())
                .build();
    }

    private Map<String, Map<String, List<SensorCalibration>>> latestTwo(Collection<String> deviceUids) {
        Map<String, Map<String, List<SensorCalibration>>> grouped = new LinkedHashMap<>();
        for (SensorCalibration c : calibrationRepository.findLatestTwoForEach(deviceUids)) {
            grouped.computeIfAbsent(c.getDeviceUid(), k -> new LinkedHashMap<>())
                    .computeIfAbsent(c.getSensorType(), k -> new ArrayList<>())
                    .add(c);
        }
        return grouped;
    }
}
