package com.weatherplatform.sensorservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * One calibration of one sensor: a reference instrument and the sensor read
 * side by side, and the correction that follows from the difference.
 *
 * Records are never updated. The newest is the one applied to incoming
 * readings; the older ones are what drift is estimated from.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sensor_calibration")
public class SensorCalibration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, length = 100)
    private String deviceUid;

    @Column(name = "sensor_type", nullable = false, length = 50)
    private String sensorType;

    /** What the reference instrument read. */
    @Column(name = "reference_value", nullable = false, precision = 9, scale = 3)
    private BigDecimal referenceValue;

    /** What the sensor itself read at the same moment, uncorrected. */
    @Column(name = "sensor_value", nullable = false, precision = 9, scale = 3)
    private BigDecimal sensorValue;

    /** referenceValue - sensorValue; added to every raw reading while this calibration is active. */
    @Column(name = "offset_value", nullable = false, precision = 9, scale = 3)
    private BigDecimal offsetValue;

    @Column(name = "calibrated_at", nullable = false)
    private LocalDateTime calibratedAt;

    @Column(name = "valid_until", nullable = false)
    private LocalDateTime validUntil;

    @Column(name = "performed_by", length = 255)
    private String performedBy;

    @Column(length = 500)
    private String notes;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
