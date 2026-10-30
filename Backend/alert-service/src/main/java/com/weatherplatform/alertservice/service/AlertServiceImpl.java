package com.weatherplatform.alertservice.service;

import com.weatherplatform.alertservice.dto.AlertResponse;
import com.weatherplatform.alertservice.dto.AlertRuleRequest;
import com.weatherplatform.alertservice.dto.AlertRuleResponse;
import com.weatherplatform.alertservice.dto.AlertStatusUpdateRequest;
import com.weatherplatform.alertservice.dto.AnomalyResponse;
import com.weatherplatform.alertservice.entity.Alert;
import com.weatherplatform.alertservice.entity.AlertRule;
import com.weatherplatform.alertservice.entity.AlertSeverity;
import com.weatherplatform.alertservice.entity.AlertStatus;
import com.weatherplatform.alertservice.entity.MetricType;
import com.weatherplatform.alertservice.event.AnomalyDetectedEvent;
import com.weatherplatform.alertservice.event.DeviceOfflineEvent;
import com.weatherplatform.alertservice.event.SensorFailureDetectedEvent;
import com.weatherplatform.alertservice.event.SensorReadingEvent;
import com.weatherplatform.alertservice.exception.ResourceNotFoundException;
import com.weatherplatform.alertservice.mapper.AlertMapper;
import com.weatherplatform.alertservice.repository.AlertRepository;
import com.weatherplatform.alertservice.repository.AlertRuleRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AlertServiceImpl implements AlertService {

    private final AlertRuleRepository ruleRepository;
    private final AlertRepository alertRepository;
    private final AlertMapper mapper;
    private final MeterRegistry meterRegistry;

    /**
     * How long an open alert suppresses repeats of the same condition.
     *
     * A station sitting just above a threshold reports every minute. Without a
     * cooldown that is one alert per minute, indefinitely, for a single fault.
     */
    @Value("${alert.dedupe.cooldown-minutes:30}")
    private long cooldownMinutes;

    /** Offline alerts get a longer window — a station stays down for a while. */
    @Value("${alert.offline.cooldown-minutes:60}")
    private long offlineCooldownMinutes;

    // ------------------------------------------------------------------
    // Event handling
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public void processSensorReading(SensorReadingEvent event) {
        log.debug("Evaluating alert rules for device: {}", event.getDeviceUid());

        List<AlertRule> activeRules = ruleRepository.findByDeviceUidAndActiveTrue(event.getDeviceUid());
        if (activeRules.isEmpty()) {
            return;
        }

        for (AlertRule rule : activeRules) {
            BigDecimal valueToCheck = getValueForMetric(event, rule.getMetricType());
            if (valueToCheck != null && isConditionMet(valueToCheck, rule)) {
                String message = String.format("%s %s %s on %s (reading: %s)",
                        rule.getMetricType(), humanise(rule.getConditionType().name()),
                        rule.getThreshold(), event.getDeviceUid(), valueToCheck);

                raise(Alert.builder()
                        .deviceUid(event.getDeviceUid())
                        .ruleId(rule.getId())
                        .metricType(rule.getMetricType())
                        .message(truncate(message))
                        .triggerValue(valueToCheck)
                        .severity(AlertSeverity.WARNING)
                        .dedupeKey(dedupeKey(event.getDeviceUid(), rule.getMetricType().name(), String.valueOf(rule.getId())))
                        .build(), cooldownMinutes);
            }
        }
    }

    @Override
    @Transactional
    public void processSensorFailure(SensorFailureDetectedEvent event) {
        log.warn("Sensor failure reported for device {} sensor {}", event.getDeviceUid(), event.getSensorType());

        // A FAILED sensor is unusable; WARNING means degraded but still reporting.
        AlertSeverity severity = "FAILED".equalsIgnoreCase(event.getStatus())
                ? AlertSeverity.CRITICAL
                : AlertSeverity.WARNING;

        String message = String.format("Sensor %s on %s is %s: %s (value: %s)",
                event.getSensorType(), event.getDeviceUid(), event.getStatus(),
                event.getFailureReason(), event.getTriggerValue());

        raise(Alert.builder()
                .deviceUid(event.getDeviceUid())
                .ruleId(0L)
                .metricType(MetricType.SENSOR_FAILURE)
                .message(truncate(message))
                .triggerValue(BigDecimal.ZERO)
                .severity(severity)
                .sensorType(event.getSensorType())
                // Keyed on status too, so a degraded sensor that later fails
                // outright raises a new, more severe alert rather than being
                // suppressed as "the same problem".
                .dedupeKey(dedupeKey(event.getDeviceUid(), "SENSOR_FAILURE",
                        event.getSensorType() + ":" + event.getStatus()))
                .build(), cooldownMinutes);
    }

    @Override
    @Transactional
    public void processAnomaly(AnomalyDetectedEvent event) {
        log.warn("AI anomaly reported for device {} sensor {}", event.getDeviceUid(), event.getSensor());

        String sensor = event.getSensor() != null ? event.getSensor() : "environment";
        String reason = event.getReason() != null ? event.getReason() : "Anomalous reading detected by the AI model";

        String message = String.format("AI anomaly on %s (%s): %s", event.getDeviceUid(), sensor, reason);

        raise(Alert.builder()
                .deviceUid(event.getDeviceUid())
                .ruleId(0L)
                .metricType(MetricType.ANOMALY)
                .message(truncate(message))
                .triggerValue(toBigDecimal(event.getValue()))
                .severity(parseSeverity(event.getSeverity()))
                .sensorType(sensor)
                // Structured, so the API never has to parse these back out of
                // the message string the way it used to.
                .anomalyScore(toScore(event.getScore()))
                .expectedValue(event.getExpectedValue() != null
                        ? BigDecimal.valueOf(event.getExpectedValue()) : null)
                .modelName(event.getModelName())
                .modelVersion(event.getModelVersion())
                .dedupeKey(dedupeKey(event.getDeviceUid(), "ANOMALY", sensor))
                .build(), cooldownMinutes);
    }

    @Override
    @Transactional
    public void processDeviceOffline(DeviceOfflineEvent event) {
        log.warn("Device offline reported for {}", event.getDeviceUid());

        String message = String.format("Station %s (%s) stopped reporting; last seen %s",
                event.getDeviceUid(), event.getDeviceName(), event.getLastSeenAt());

        raise(Alert.builder()
                .deviceUid(event.getDeviceUid())
                .ruleId(0L)
                .metricType(MetricType.DEVICE_OFFLINE)
                .message(truncate(message))
                .triggerValue(BigDecimal.ZERO)
                .severity(AlertSeverity.CRITICAL)
                .dedupeKey(dedupeKey(event.getDeviceUid(), "DEVICE_OFFLINE", "heartbeat"))
                .build(), offlineCooldownMinutes);
    }

    // ------------------------------------------------------------------
    // Deduplication
    // ------------------------------------------------------------------

    /**
     * Creates the alert, or folds it into the open one for the same condition.
     *
     * Three outcomes:
     * <ul>
     *   <li>no open alert          → create it</li>
     *   <li>open, within cooldown  → bump the occurrence count, stay quiet</li>
     *   <li>open, cooldown expired → bump the count and re-stamp, so the alert
     *       surfaces again for an operator who has not acted on it</li>
     * </ul>
     */
    private void raise(Alert candidate, long cooldown) {
        Optional<Alert> existing = alertRepository
                .findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(candidate.getDedupeKey(), AlertStatus.ACTIVE);

        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);

        if (existing.isPresent()) {
            Alert open = existing.get();
            LocalDateTime lastSeen = open.getLastTriggeredAt() != null
                    ? open.getLastTriggeredAt()
                    : open.getTriggeredAt();

            open.setOccurrenceCount(open.getOccurrenceCount() + 1);
            open.setLastTriggeredAt(now);
            open.setTriggerValue(candidate.getTriggerValue());

            boolean cooledDown = lastSeen != null
                    && lastSeen.plus(Duration.ofMinutes(cooldown)).isBefore(now);

            if (cooledDown) {
                // Still unresolved after the cooldown — refresh the detail so the
                // operator sees the current reading, not the one from hours ago.
                open.setMessage(candidate.getMessage());
                open.setSeverity(candidate.getSeverity());
                meterRegistry.counter("alerts.renotified",
                        "metric", candidate.getMetricType().name()).increment();
                log.info("Alert {} still open after cooldown; re-surfacing", open.getId());
            } else {
                meterRegistry.counter("alerts.suppressed",
                        "metric", candidate.getMetricType().name()).increment();
                log.debug("Suppressed duplicate alert for key {} (occurrence {})",
                        open.getDedupeKey(), open.getOccurrenceCount());
            }

            alertRepository.save(open);
            return;
        }

        candidate.setLastTriggeredAt(now);
        Alert saved = alertRepository.save(candidate);

        meterRegistry.counter("alerts.created",
                "metric", candidate.getMetricType().name(),
                "severity", candidate.getSeverity().name()).increment();
        log.warn("Alert {} raised [{}]: {}", saved.getId(), saved.getSeverity(), saved.getMessage());
    }

    private String dedupeKey(String deviceUid, String category, String discriminator) {
        return deviceUid + "|" + category + "|" + discriminator;
    }

    // ------------------------------------------------------------------
    // Rules
    // ------------------------------------------------------------------

    private BigDecimal getValueForMetric(SensorReadingEvent event, MetricType metricType) {
        return switch (metricType) {
            case TEMPERATURE -> event.getTemperature();
            case HUMIDITY -> event.getHumidity();
            case PRESSURE -> event.getPressure();
            case AIR_QUALITY -> event.getAirQuality() != null ? BigDecimal.valueOf(event.getAirQuality()) : null;
            case OXYGEN -> event.getOxygen();
            default -> null;
        };
    }

    private boolean isConditionMet(BigDecimal value, AlertRule rule) {
        int comparison = value.compareTo(rule.getThreshold());
        return switch (rule.getConditionType()) {
            case GREATER_THAN -> comparison > 0;
            case LESS_THAN -> comparison < 0;
            case EQUALS -> comparison == 0;
        };
    }

    // ------------------------------------------------------------------
    // Queries and administration
    // ------------------------------------------------------------------

    @Override
    @Transactional
    public AlertRuleResponse createRule(AlertRuleRequest request) {
        AlertRule saved = ruleRepository.save(mapper.toEntity(request));
        log.info("Created alert rule {} for device {}", saved.getId(), saved.getDeviceUid());
        return mapper.toResponse(saved);
    }

    @Override
    public List<AlertRuleResponse> getRulesForDevice(String deviceUid) {
        return ruleRepository.findByDeviceUid(deviceUid).stream().map(mapper::toResponse).toList();
    }

    @Override
    @Transactional
    public void deleteRule(Long ruleId) {
        if (!ruleRepository.existsById(ruleId)) {
            throw new ResourceNotFoundException("Rule not found with id " + ruleId);
        }
        ruleRepository.deleteById(ruleId);
        log.info("Deleted alert rule {}", ruleId);
    }

    @Override
    public List<AlertResponse> getAlertsForDevice(String deviceUid, String status) {
        List<Alert> alerts = (status != null && !status.isBlank())
                ? alertRepository.findByDeviceUidAndStatusOrderByTriggeredAtDesc(deviceUid, parseStatus(status))
                : alertRepository.findByDeviceUidOrderByTriggeredAtDesc(deviceUid);
        return alerts.stream().map(mapper::toResponse).toList();
    }

    @Override
    public List<AlertResponse> getAllAlerts(String status) {
        List<Alert> alerts = (status != null && !status.isBlank())
                ? alertRepository.findByStatusOrderByTriggeredAtDesc(parseStatus(status))
                : alertRepository.findAllByOrderByTriggeredAtDesc();
        return alerts.stream().map(mapper::toResponse).toList();
    }

    @Override
    public List<AnomalyResponse> getAnomalies() {
        // Reads the stored columns directly — the previous implementation
        // recovered the score by parsing the human-readable message, which broke
        // whenever the wording changed and silently defaulted to 80.
        return alertRepository.findByMetricTypeOrderByTriggeredAtDesc(MetricType.ANOMALY).stream()
                .map(a -> AnomalyResponse.builder()
                        .id("AN-" + a.getId())
                        .stationUid(a.getDeviceUid())
                        .parameter(a.getSensorType() != null ? a.getSensorType() : "environment")
                        .actual(a.getTriggerValue() != null ? a.getTriggerValue().toPlainString() : null)
                        .expected(a.getExpectedValue() != null ? a.getExpectedValue().toPlainString() : null)
                        .score(a.getAnomalyScore())
                        .severity(a.getSeverity() != null ? a.getSeverity().name() : AlertSeverity.WARNING.name())
                        .explanation(a.getMessage())
                        .modelName(a.getModelName())
                        .modelVersion(a.getModelVersion())
                        .timestamp(a.getTriggeredAt())
                        .build())
                .toList();
    }

    @Override
    @Transactional
    public AlertResponse updateAlertStatus(Long alertId, AlertStatusUpdateRequest request) {
        Alert alert = alertRepository.findById(alertId)
                .orElseThrow(() -> new ResourceNotFoundException("Alert not found with id " + alertId));

        alert.setStatus(request.getStatus());
        // Only a terminal status closes the alert; reopening clears the stamp.
        if (request.getStatus() == AlertStatus.RESOLVED || request.getStatus() == AlertStatus.DISMISSED) {
            alert.setResolvedAt(LocalDateTime.now(ZoneOffset.UTC));
        } else {
            alert.setResolvedAt(null);
        }

        Alert saved = alertRepository.save(alert);
        log.info("Alert {} moved to {}", alertId, request.getStatus());
        return mapper.toResponse(saved);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private AlertStatus parseStatus(String status) {
        try {
            return AlertStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown alert status '" + status + "'. Expected one of: ACTIVE, RESOLVED, DISMISSED");
        }
    }

    private AlertSeverity parseSeverity(String severity) {
        if (severity == null) {
            return AlertSeverity.WARNING;
        }
        return switch (severity.trim().toUpperCase(Locale.ROOT)) {
            case "CRITICAL", "HIGH", "SEVERE" -> AlertSeverity.CRITICAL;
            case "INFO", "LOW" -> AlertSeverity.INFO;
            default -> AlertSeverity.WARNING;
        };
    }

    /** Clamps to [0, 1]; a model returning something outside that is a bug, not data. */
    private BigDecimal toScore(Double score) {
        if (score == null) {
            return null;
        }
        double clamped = Math.max(0.0, Math.min(1.0, score));
        return BigDecimal.valueOf(clamped).setScale(4, java.math.RoundingMode.HALF_UP);
    }

    private BigDecimal toBigDecimal(Double value) {
        return value != null ? BigDecimal.valueOf(value) : BigDecimal.ZERO;
    }

    /** The message column is 255 chars; a long model explanation must not fail the insert. */
    private String truncate(String message) {
        return message.length() <= 255 ? message : message.substring(0, 252) + "...";
    }

    private String humanise(String conditionName) {
        return conditionName.toLowerCase(Locale.ROOT).replace('_', ' ');
    }
}
