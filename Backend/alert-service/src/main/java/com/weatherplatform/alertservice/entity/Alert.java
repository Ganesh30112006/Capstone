package com.weatherplatform.alertservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "alerts", indexes = {
        @Index(name = "idx_alerts_device_triggered", columnList = "device_uid, triggered_at DESC"),
        @Index(name = "idx_alerts_status_triggered", columnList = "status, triggered_at DESC"),
        @Index(name = "idx_alerts_dedupe", columnList = "dedupe_key, status")
})
public class Alert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, length = 100)
    private String deviceUid;

    /** 0 for alerts not raised by a configured rule (failures, offline, anomalies). */
    @Column(name = "rule_id", nullable = false)
    private Long ruleId;

    @Enumerated(EnumType.STRING)
    @Column(name = "metric_type", nullable = false, length = 20)
    private MetricType metricType;

    @Column(nullable = false, length = 255)
    private String message;

    @Column(name = "trigger_value", nullable = false, precision = 10, scale = 2)
    private BigDecimal triggerValue;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AlertStatus status = AlertStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private AlertSeverity severity = AlertSeverity.WARNING;

    /**
     * Stable identity of "the same problem", e.g. {@code STATION-001|TEMPERATURE|42}.
     *
     * Deduplication keys on this: while an alert with this key is ACTIVE, a
     * repeat condition bumps {@link #occurrenceCount} instead of creating a new
     * row. Without it, a station sitting just over a threshold would raise a
     * fresh alert every reporting interval and bury the operator.
     */
    @Column(name = "dedupe_key", nullable = false, length = 200)
    private String dedupeKey;

    /** How many times this condition has recurred while the alert stayed open. */
    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private Integer occurrenceCount = 1;

    /** Most recent recurrence — drives the cooldown window. */
    @Column(name = "last_triggered_at")
    private LocalDateTime lastTriggeredAt;

    // ---------------------------------------------------------------------
    // AI anomaly detail. Populated only for MetricType.ANOMALY.
    // Stored as columns rather than parsed back out of the message string,
    // which was fragile and lost precision.
    // ---------------------------------------------------------------------

    @Column(name = "sensor_type", length = 50)
    private String sensorType;

    /**
     * The model's anomaly score in [0, 1].
     *
     * This is a score, not a calibrated confidence — it expresses how far the
     * reading sits from the model's expectation, not a probability that
     * something is wrong. Named accordingly so the UI cannot present it as one.
     */
    @Column(name = "anomaly_score", precision = 5, scale = 4)
    private BigDecimal anomalyScore;

    /** What the forecast model expected, for the "actual vs expected" display. */
    @Column(name = "expected_value", precision = 10, scale = 2)
    private BigDecimal expectedValue;

    @Column(name = "model_name", length = 100)
    private String modelName;

    @Column(name = "model_version", length = 50)
    private String modelVersion;

    @CreationTimestamp
    @Column(name = "triggered_at", nullable = false, updatable = false)
    private LocalDateTime triggeredAt;

    /**
     * Set explicitly when an operator resolves or dismisses the alert.
     *
     * Previously carried @UpdateTimestamp, which stamped a resolution time onto
     * every alert the moment it was written — so an ACTIVE alert appeared to be
     * already resolved.
     */
    @Column(name = "resolved_at")
    private LocalDateTime resolvedAt;
}
