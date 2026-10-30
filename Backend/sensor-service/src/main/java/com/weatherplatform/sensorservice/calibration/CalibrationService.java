package com.weatherplatform.sensorservice.calibration;

import com.weatherplatform.sensorservice.config.CalibrationProperties;
import com.weatherplatform.sensorservice.dto.CalibrationRequest;
import com.weatherplatform.sensorservice.dto.CalibrationResponse;
import com.weatherplatform.sensorservice.entity.SensorCalibration;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.exception.CalibrationRejectedException;
import com.weatherplatform.sensorservice.exception.ResourceNotFoundException;
import com.weatherplatform.sensorservice.repository.SensorCalibrationRepository;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Records calibrations and applies them to incoming telemetry.
 *
 * <pre>
 *   raw reading  -->  offset of the calibration in force  -->  corrected reading
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationService {

    /** Tolerated clock skew between an operator's device and this service. */
    private static final Duration FUTURE_TOLERANCE = Duration.ofMinutes(1);

    private final SensorCalibrationRepository calibrationRepository;
    private final SensorDataRepository dataRepository;
    private final CalibrationProperties properties;
    private final Clock clock;

    @Transactional
    public CalibrationResponse record(String deviceUid, CalibrationRequest request, String performedBy) {
        String sensorType = request.getSensorType();
        if (!properties.isCalibratable(sensorType)) {
            throw new CalibrationRejectedException("'" + sensorType + "' cannot be calibrated. Calibratable sensors: "
                    + String.join(", ", properties.getSensors().keySet()) + ".");
        }

        SensorData latest = dataRepository.findTopByDeviceUidOrderByRecordedAtDesc(deviceUid)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "No readings have ever been received from device " + deviceUid
                                + ", so there is no sensor here to calibrate."));

        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime calibratedAt = request.getCalibratedAt() != null ? request.getCalibratedAt() : now;
        if (calibratedAt.isAfter(now.plus(FUTURE_TOLERANCE))) {
            throw new CalibrationRejectedException("calibratedAt " + calibratedAt + " is in the future.");
        }

        BigDecimal sensorValue = request.getSensorValue() != null
                ? request.getSensorValue()
                : pairWithLatestReading(latest, sensorType, request.getCalibratedAt() != null, now);

        BigDecimal offset = CalibrationMath.offset(request.getReferenceValue(), sensorValue);
        CalibrationProperties.Policy policy = properties.policyFor(sensorType);
        if (offset.abs().doubleValue() > policy.getMaxOffset()) {
            throw new CalibrationRejectedException(String.format(
                    "A %s correction of %s exceeds the %s limit for this sensor type. A sensor this far out "
                            + "needs inspecting, not correcting, or one of the two values was mistyped.",
                    sensorType, offset.stripTrailingZeros().toPlainString(),
                    BigDecimal.valueOf(policy.getMaxOffset()).stripTrailingZeros().toPlainString()));
        }

        SensorCalibration saved = calibrationRepository.save(SensorCalibration.builder()
                .deviceUid(deviceUid)
                .sensorType(sensorType)
                .referenceValue(request.getReferenceValue().setScale(CalibrationMath.OFFSET_SCALE, RoundingMode.HALF_UP))
                .sensorValue(sensorValue.setScale(CalibrationMath.OFFSET_SCALE, RoundingMode.HALF_UP))
                .offsetValue(offset)
                .calibratedAt(calibratedAt)
                .validUntil(calibratedAt.plusDays(policy.getValidityDays()))
                .performedBy(performedBy)
                .notes(request.getNotes())
                .build());

        log.info("Recorded {} calibration for {}: reference {} vs sensor {} -> offset {}",
                sensorType, deviceUid, saved.getReferenceValue(), saved.getSensorValue(), saved.getOffsetValue());
        return toResponse(saved, now);
    }

    /**
     * A device's calibration history, newest first. Empty is an answer: never
     * calibrated.
     *
     * Only the newest record per sensor is in force; every older one is
     * SUPERSEDED, whatever its own validity dates say.
     */
    public List<CalibrationResponse> history(String deviceUid) {
        LocalDateTime now = LocalDateTime.now(clock);
        java.util.Set<String> seen = new java.util.HashSet<>();
        return calibrationRepository.findByDeviceUidOrderByCalibratedAtDescIdDesc(deviceUid).stream()
                .map(c -> {
                    CalibrationResponse response = toResponse(c, now);
                    if (!seen.add(c.getSensorType())) {
                        response.setStatus(CalibrationStatus.SUPERSEDED);
                    }
                    return response;
                })
                .toList();
    }

    /**
     * Rewrites a reading's main columns to corrected values, keeping the raw ones
     * beside them. Uses the calibration in force when the reading was taken, so
     * a late-arriving reading is corrected the way it would have been on time.
     *
     * Always records the raw values, even with no calibration in force: a NULL
     * raw column is reserved for rows that predate calibration altogether.
     */
    public void applyCorrections(SensorData reading) {
        Map<String, BigDecimal> offsets = new HashMap<>();
        for (SensorCalibration c : calibrationRepository.findActiveForDevice(reading.getDeviceUid(), reading.getRecordedAt())) {
            offsets.put(c.getSensorType(), c.getOffsetValue());
        }
        correct(reading, offsets);
    }

    /** The pure half of {@link #applyCorrections}, for testing without a database. */
    static void correct(SensorData reading, Map<String, BigDecimal> offsets) {
        reading.setRawTemperature(reading.getTemperature());
        reading.setRawHumidity(reading.getHumidity());
        reading.setRawAirQuality(reading.getAirQuality());
        reading.setRawPressure(reading.getPressure());
        reading.setRawOxygen(reading.getOxygen());

        reading.setTemperature(CalibrationMath.correct("temperature", reading.getRawTemperature(), offsets.get("temperature")));
        reading.setHumidity(CalibrationMath.correct("humidity", reading.getRawHumidity(), offsets.get("humidity")));
        reading.setAirQuality(CalibrationMath.correctIndex(reading.getRawAirQuality(), offsets.get("airQuality")));
        reading.setPressure(CalibrationMath.correct("pressure", reading.getRawPressure(), offsets.get("pressure")));
        reading.setOxygen(CalibrationMath.correct("oxygen", reading.getRawOxygen(), offsets.get("oxygen")));
    }

    private BigDecimal pairWithLatestReading(SensorData latest, String sensorType, boolean backdated, LocalDateTime now) {
        if (backdated) {
            throw new CalibrationRejectedException("sensorValue is required when calibratedAt is given: the "
                    + "sensor's current reading says nothing about what it read at that earlier time.");
        }
        long age = Duration.between(latest.getRecordedAt(), now).getSeconds();
        if (age > properties.getMaxReadingAgeSeconds()) {
            throw new CalibrationRejectedException(String.format(
                    "The newest reading from %s is %d seconds old, and a calibration pairs the reference with a "
                            + "reading taken at the same time (within %d seconds). Supply sensorValue, or "
                            + "calibrate while the station is reporting.",
                    latest.getDeviceUid(), age, properties.getMaxReadingAgeSeconds()));
        }
        BigDecimal raw = sensedValue(latest, sensorType);
        if (raw == null) {
            throw new CalibrationRejectedException("The newest reading from " + latest.getDeviceUid()
                    + " has no " + sensorType + " value to calibrate against.");
        }
        return raw;
    }

    private static BigDecimal sensedValue(SensorData reading, String sensorType) {
        return switch (sensorType) {
            case "temperature" -> reading.sensedTemperature();
            case "humidity" -> reading.sensedHumidity();
            case "airQuality" -> reading.sensedAirQuality() == null ? null : BigDecimal.valueOf(reading.sensedAirQuality());
            case "pressure" -> reading.sensedPressure();
            case "oxygen" -> reading.sensedOxygen();
            default -> null;
        };
    }

    CalibrationResponse toResponse(SensorCalibration c, LocalDateTime now) {
        return CalibrationResponse.builder()
                .id(c.getId())
                .deviceUid(c.getDeviceUid())
                .sensorType(c.getSensorType())
                .referenceValue(c.getReferenceValue())
                .sensorValue(c.getSensorValue())
                .offset(c.getOffsetValue())
                .calibratedAt(c.getCalibratedAt())
                .validUntil(c.getValidUntil())
                .performedBy(c.getPerformedBy())
                .notes(c.getNotes())
                .status(CalibrationMath.status(c, now, properties.getDueSoonDays()))
                .build();
    }
}
