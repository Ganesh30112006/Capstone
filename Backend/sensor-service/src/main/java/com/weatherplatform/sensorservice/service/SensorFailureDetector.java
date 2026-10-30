package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.config.RabbitMQConfig;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.entity.SensorHealth;
import com.weatherplatform.sensorservice.event.SensorFailureDetectedEvent;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import com.weatherplatform.sensorservice.config.SensorThresholdProperties;
import com.weatherplatform.sensorservice.repository.SensorHealthRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class SensorFailureDetector {

    /** No diagnostics on file. Distinct from HEALTHY, which is a passed check. */
    public static final String STATUS_UNKNOWN = "UNKNOWN";

    /**
     * How long a still-unhealthy sensor goes without being re-announced.
     *
     * A stuck sensor is one condition, not one condition per reading, and this
     * detector was publishing an event every time the station reported: a
     * flatlined station produced four events per reading, for as long as it
     * stayed flatlined. Over one afternoon that was 4,027 events and the same
     * number of WARN lines in two services, all describing the state that the
     * first event already described.
     *
     * Suppression is deliberately not silence. The state is re-announced on
     * this interval so that a consumer which started late, restarted, or lost
     * its queue still learns the sensor is unhealthy, rather than inferring
     * health from the absence of events.
     */
    static final Duration REASSERT_AFTER = Duration.ofMinutes(5);

    /** What was last published per device and sensor, to suppress repeats. */
    private final Map<String, Assertion> published = new ConcurrentHashMap<>();

    /** The value each sensor has been holding, and since when. */
    private final Map<String, Held> heldValues = new ConcurrentHashMap<>();

    /** When each sensor currently reporting healthy started doing so. */
    private final Map<String, Instant> healthySince = new ConcurrentHashMap<>();

    private record Assertion(String status, Instant at) {}

    private record Held(double value, LocalDateTime since, int samples) {}

    /** One check's outcome: how bad, which check decided it, and since when a flatline began. */
    record Verdict(String status, SensorCondition condition, LocalDateTime flatlineSince) {
        static Verdict healthy() { return new Verdict("HEALTHY", SensorCondition.NONE, null); }
        static Verdict failed(SensorCondition condition) { return new Verdict("FAILED", condition, null); }
        static Verdict rapidChange() { return new Verdict("WARNING", SensorCondition.RAPID_CHANGE, null); }
        static Verdict flatline(LocalDateTime since) { return new Verdict("WARNING", SensorCondition.FLATLINE, since); }
    }

    private final SensorHealthRepository healthRepository;
    private final SensorDataRepository dataRepository;
    private final RabbitTemplate rabbitTemplate;
    private final SensorThresholdProperties props;
    private final SensorHealthLedger ledger;

    public Map<String, String> evaluateAndRecordHealth(SensorDataRequest request) {
        String deviceUid = request.getDeviceUid();

        // Fetch recent readings for stuck/rate-of-change checks
        List<SensorData> recent = dataRepository.findByDeviceUidOrderByRecordedAtDesc(deviceUid, PageRequest.of(0, 5)).getContent();

        // The evaluation clock is taken once, so every check on this reading
        // measures elapsed time from the same instant.
        LocalDateTime now = LocalDateTime.now();

        // Every check judges the sensor, so it runs on the raw values the sensor
        // reported -- the request has not been calibrated yet -- and compares
        // against raw history. Comparing corrected values would make a new
        // calibration look like a sudden jump.
        Map<String, Verdict> verdicts = new LinkedHashMap<>();
        verdicts.put("temperature", checkTemperature(deviceUid, request.getTemperature(), recent, now));
        verdicts.put("humidity", checkHumidity(deviceUid, request.getHumidity(), recent, now));
        verdicts.put("airQuality", checkAirQuality(deviceUid, request.getAirQuality(), now));
        verdicts.put("pressure", checkPressure(deviceUid, request.getPressure(), recent, now));
        if (request.getOxygen() != null) {
            verdicts.put("oxygen", checkOxygen(request.getOxygen()));
        }

        Map<String, String> statuses = new LinkedHashMap<>();
        verdicts.forEach((sensorType, verdict) -> statuses.put(sensorType,
                updateSensorHealth(deviceUid, sensorType, verdict, triggerValue(request, sensorType), now)));

        // Not evaluated is UNKNOWN, not HEALTHY. Oxygen is optional on the wire
        // and a reading without it says nothing about the probe; rain is a
        // yes/no this detector has no check for at all. Both used to be reported
        // HEALTHY, and the digital twin stored that as a passed check.
        statuses.putIfAbsent("oxygen", STATUS_UNKNOWN);
        statuses.put("rain", STATUS_UNKNOWN);

        ledger.record(deviceUid, statuses, now);

        return statuses;
    }

    public SensorHealthResponse getDeviceSensorHealth(String deviceUid) {
        return summarise(deviceUid, healthRepository.findByDeviceUid(deviceUid));
    }

    /**
     * Diagnostics for many devices in a single query, keyed by device UID.
     *
     * A device with no health record at all is <strong>absent from the map</strong>
     * rather than present with an empty, healthy-looking summary. The caller can
     * then distinguish "this station has never been evaluated" from "this
     * station was evaluated and every probe passed" -- which a zero-length list
     * of failures cannot express on its own.
     */
    public Map<String, SensorHealthResponse> getSensorHealthForEach(Collection<String> deviceUids) {
        Map<String, List<SensorHealth>> grouped = healthRepository.findByDeviceUidIn(deviceUids).stream()
                .collect(Collectors.groupingBy(SensorHealth::getDeviceUid, LinkedHashMap::new, Collectors.toList()));

        Map<String, SensorHealthResponse> reports = new LinkedHashMap<>();
        grouped.forEach((uid, records) -> reports.put(uid, summarise(uid, records)));
        return reports;
    }

    private SensorHealthResponse summarise(String deviceUid, List<SensorHealth> records) {
        Map<String, SensorHealthResponse.SensorStatusDetail> map = new LinkedHashMap<>();
        // A station nothing has ever evaluated is UNKNOWN, not HEALTHY. Starting
        // at HEALTHY made "no diagnostics on file" indistinguishable from "every
        // probe passed", which is the one claim this endpoint must not invent.
        String overall = records.isEmpty() ? STATUS_UNKNOWN : "HEALTHY";
        LocalDateTime lastChecked = null;

        for (SensorHealth rec : records) {
            SensorThresholdProperties.Range range = rangeFor(rec.getSensorType());
            map.put(rec.getSensorType(), SensorHealthResponse.SensorStatusDetail.builder()
                    .status(rec.getStatus())
                    .details(rec.getDetails())
                    .lastUpdated(rec.getLastCheckedAt())
                    .condition(rec.getCondition())
                    .lastHealthyAt(rec.getLastHealthyAt())
                    // Unknown, not false, on a record whose condition was never stored.
                    .flatlined(rec.getCondition() == null ? null
                            : SensorCondition.FLATLINE.name().equals(rec.getCondition()))
                    .flatlineSince(rec.getFlatlineSince())
                    .expectedMin(range == null ? null : range.getMin())
                    .expectedMax(range == null ? null : range.getMax())
                    .build());

            if ("FAILED".equals(rec.getStatus())) {
                overall = "FAILED";
            } else if ("WARNING".equals(rec.getStatus()) && !"FAILED".equals(overall)) {
                overall = "WARNING";
            }
            // Null until a record supplies one: stamping now() here would date
            // the report to this request rather than to the check it describes.
            if (rec.getLastCheckedAt() != null
                    && (lastChecked == null || rec.getLastCheckedAt().isAfter(lastChecked))) {
                lastChecked = rec.getLastCheckedAt();
            }
        }

        return SensorHealthResponse.builder()
                .deviceUid(deviceUid)
                .overallStatus(overall)
                .sensors(map)
                .lastCheckedAt(lastChecked)
                .build();
    }

    private String updateSensorHealth(String deviceUid, String sensorType, Verdict verdict,
                                      String triggerValue, LocalDateTime now) {
        String status = verdict.status();
        Optional<SensorHealth> existing = healthRepository.findByDeviceUidAndSensorType(deviceUid, sensorType);
        SensorHealth health = existing.orElse(SensorHealth.builder()
                .deviceUid(deviceUid)
                .sensorType(sensorType)
                .build());

        health.setStatus(status);
        health.setCondition(verdict.condition().name());
        health.setDetails(describe(sensorType, verdict, triggerValue));
        health.setFlatlineSince(verdict.flatlineSince());
        if ("HEALTHY".equals(status)) {
            health.setLastHealthyAt(now);
        }
        healthRepository.save(health);

        String key = deviceUid + '|' + sensorType;
        if ("HEALTHY".equals(status)) {
            Instant wellSince = healthySince.computeIfAbsent(key, k -> Instant.now());
            // One clean reading is not a recovery. A value sitting on a
            // threshold alternates between sides, and clearing on the first
            // healthy one makes every other reading a brand-new failure.
            if (Duration.between(wellSince, Instant.now()).getSeconds()
                    >= props.getRecoveryHoldSeconds()) {
                published.remove(key);
                healthySince.remove(key);
            }
        } else {
            healthySince.remove(key);
            if (shouldAnnounce(key, status)) {
                publishFailureEvent(deviceUid, sensorType, status, health.getDetails(), triggerValue);
            }
        }

        return status;
    }

    /** A sentence an operator can act on, naming the value and the limit it broke. */
    private String describe(String sensorType, Verdict verdict, String value) {
        return switch (verdict.condition()) {
            case NONE -> "Sensor functioning within normal tolerances";
            case MISSING -> "No " + sensorType + " value in the reading";
            case OUT_OF_RANGE -> {
                SensorThresholdProperties.Range range = rangeFor(sensorType);
                yield "Value " + value + " is outside the sensor's range"
                        + (range == null ? "" : " (" + range.getMin() + " to " + range.getMax() + ")");
            }
            case RAPID_CHANGE -> {
                SensorThresholdProperties.Range range = rangeFor(sensorType);
                yield "Value " + value + " is changing faster than"
                        + (range == null || range.getRapidChange() == null ? " is plausible"
                           : " " + range.getRapidChange() + " per minute");
            }
            case FLATLINE -> "Value " + value + " has not changed since " + verdict.flatlineSince();
        };
    }

    private SensorThresholdProperties.Range rangeFor(String sensorType) {
        return switch (sensorType) {
            case "temperature" -> props.getTemperature();
            case "humidity" -> props.getHumidity();
            case "airQuality" -> props.getAirQuality();
            case "pressure" -> props.getPressure();
            case "oxygen" -> props.getOxygen();
            default -> null;
        };
    }

    private static String triggerValue(SensorDataRequest request, String sensorType) {
        Object value = switch (sensorType) {
            case "temperature" -> request.getTemperature();
            case "humidity" -> request.getHumidity();
            case "airQuality" -> request.getAirQuality();
            case "pressure" -> request.getPressure();
            case "oxygen" -> request.getOxygen();
            default -> null;
        };
        return value == null ? "null" : value.toString();
    }

    /**
     * True for a condition not yet announced, one whose status has changed, or
     * one whose last announcement is older than {@link #REASSERT_AFTER}.
     */
    private boolean shouldAnnounce(String key, String status) {
        Instant now = Instant.now();
        Assertion last = published.get(key);
        if (last != null
                && last.status().equals(status)
                && Duration.between(last.at(), now).compareTo(REASSERT_AFTER) < 0) {
            return false;
        }
        published.put(key, new Assertion(status, now));
        return true;
    }

    private void publishFailureEvent(String deviceUid, String sensorType, String status, String reason, String value) {
        try {
            SensorFailureDetectedEvent event = SensorFailureDetectedEvent.builder()
                    .deviceUid(deviceUid)
                    .sensorType(sensorType)
                    .status(status)
                    .failureReason(reason)
                    .triggerValue(value)
                    .detectedAt(LocalDateTime.now())
                    .build();
            rabbitTemplate.convertAndSend(RabbitMQConfig.EXCHANGE_NAME, RabbitMQConfig.SENSOR_FAILURE_ROUTING_KEY, event);
            log.warn("Published SensorFailureDetectedEvent for device {} sensor {}", deviceUid, sensorType);
        } catch (Exception e) {
            log.error("Failed to publish SensorFailureDetectedEvent for {}", deviceUid, e);
        }
    }

    /**
     * Whether a value is changing faster than its threshold allows.
     *
     * The threshold is a rate — units per minute — and is measured against the
     * time actually elapsed, because the comparison it replaces did not.
     * `|now - previous| > limit` is only a rate if every station reports on the
     * same interval, and this fleet's interval is a deployment choice: the same
     * 25°C jump is a broken sensor between readings a second apart and an
     * ordinary afternoon between readings an hour apart. Written the old way
     * the check silently lost sensitivity as stations reported more often, and
     * raised faults on stations that reported less often.
     *
     * Measured across the whole window rather than against the immediately
     * previous reading, so ordinary sample-to-sample noise does not read as a
     * rate when readings are close together.
     */
    private boolean changingTooFast(List<SensorData> recent, double current,
                                    LocalDateTime now, Double limitPerMinute,
                                    java.util.function.Function<SensorData, BigDecimal> metric) {
        if (limitPerMinute == null || recent == null || recent.isEmpty()) return false;

        SensorData oldest = null;
        for (SensorData reading : recent) {
            if (metric.apply(reading) != null && reading.getRecordedAt() != null) {
                oldest = reading; // the list is newest-first, so the last match is the oldest
            }
        }
        if (oldest == null) return false;

        double elapsedMinutes = Duration.between(oldest.getRecordedAt(), now).toMillis() / 60_000.0;
        double difference = Math.abs(current - metric.apply(oldest).doubleValue());
        if (elapsedMinutes <= 0) {
            // Two readings with the same timestamp: no interval to divide by, so
            // the raw difference is compared rather than a rate invented from it.
            return difference > limitPerMinute;
        }
        return difference / elapsedMinutes > limitPerMinute;
    }

    /**
     * Whether a value has been identical for long enough to call the sensor
     * stuck, tracked as it arrives rather than counted out of the last few rows.
     *
     * Counting rows asks "were the last four readings the same", which is four
     * minutes of a frozen value at one reading a minute and four seconds at one
     * a second. A real sensor reporting two decimal places repeats itself for a
     * few seconds all the time. Both conditions have to hold now: enough
     * samples, and enough time.
     *
     * The tracker lives in memory, so after a restart a stuck sensor takes one
     * full window to be called stuck again. That under-reports briefly, which
     * is the right direction to fail in.
     */
    private Held track(String deviceUid, String sensorType, double value, double epsilon, LocalDateTime now) {
        String key = deviceUid + '|' + sensorType;
        return heldValues.compute(key, (k, previous) -> {
            if (previous != null && Math.abs(previous.value() - value) < epsilon) {
                return new Held(previous.value(), previous.since(), previous.samples() + 1);
            }
            return new Held(value, now, 1);
        });
    }

    private boolean isStuck(Held held, LocalDateTime now) {
        return held.samples() >= props.getStuckReadingCount()
                && Duration.between(held.since(), now).getSeconds() >= props.getStuckWindowSeconds();
    }

    /** Tracks the value and reports FLATLINE, with its start, once it has held long enough. */
    private Verdict flatlineOrHealthy(String deviceUid, String sensorType, double value,
                                      double epsilon, LocalDateTime now) {
        Held held = track(deviceUid, sensorType, value, epsilon, now);
        return isStuck(held, now) ? Verdict.flatline(held.since()) : Verdict.healthy();
    }

    private Verdict checkTemperature(String deviceUid, BigDecimal temp, List<SensorData> recent, LocalDateTime now) {
        if (temp == null) return Verdict.failed(SensorCondition.MISSING);
        double val = temp.doubleValue();
        if (val < props.getTemperature().getMin() || val > props.getTemperature().getMax()) {
            return Verdict.failed(SensorCondition.OUT_OF_RANGE);
        }
        if (changingTooFast(recent, val, now, props.getTemperature().getRapidChange(), SensorData::sensedTemperature)) {
            return Verdict.rapidChange();
        }
        return flatlineOrHealthy(deviceUid, "temperature", val, 0.001, now);
    }

    private Verdict checkHumidity(String deviceUid, BigDecimal hum, List<SensorData> recent, LocalDateTime now) {
        if (hum == null) return Verdict.failed(SensorCondition.MISSING);
        double val = hum.doubleValue();
        if (val < props.getHumidity().getMin() || val > props.getHumidity().getMax()) {
            return Verdict.failed(SensorCondition.OUT_OF_RANGE);
        }
        if (changingTooFast(recent, val, now, props.getHumidity().getRapidChange(), SensorData::sensedHumidity)) {
            return Verdict.rapidChange();
        }
        return flatlineOrHealthy(deviceUid, "humidity", val, 0.001, now);
    }

    private Verdict checkAirQuality(String deviceUid, Integer aq, LocalDateTime now) {
        if (aq == null) return Verdict.failed(SensorCondition.MISSING);
        if (aq < props.getAirQuality().getMin() || aq > props.getAirQuality().getMax()) {
            return Verdict.failed(SensorCondition.OUT_OF_RANGE);
        }
        // Air quality is an integer index, so identical means identical.
        return flatlineOrHealthy(deviceUid, "airQuality", aq, 0.5, now);
    }

    private Verdict checkPressure(String deviceUid, BigDecimal press, List<SensorData> recent, LocalDateTime now) {
        if (press == null) return Verdict.failed(SensorCondition.MISSING);
        double val = press.doubleValue();
        if (val < props.getPressure().getMin() || val > props.getPressure().getMax()) {
            return Verdict.failed(SensorCondition.OUT_OF_RANGE);
        }
        if (changingTooFast(recent, val, now, props.getPressure().getRapidChange(), SensorData::sensedPressure)) {
            return Verdict.rapidChange();
        }
        return flatlineOrHealthy(deviceUid, "pressure", val, 0.01, now);
    }

    private Verdict checkOxygen(BigDecimal ox) {
        double val = ox.doubleValue();
        if (val < props.getOxygen().getMin() || val > props.getOxygen().getMax()) {
            return Verdict.failed(SensorCondition.OUT_OF_RANGE);
        }
        return Verdict.healthy();
    }
}
