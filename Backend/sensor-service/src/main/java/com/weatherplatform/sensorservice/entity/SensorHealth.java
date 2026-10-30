package com.weatherplatform.sensorservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "sensor_health", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"device_uid", "sensor_type"})
})
public class SensorHealth {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, length = 100)
    private String deviceUid;

    @Column(name = "sensor_type", nullable = false, length = 50)
    private String sensorType;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(length = 255)
    private String details;

    /**
     * Which check decided {@link #status}: NONE, MISSING, OUT_OF_RANGE,
     * RAPID_CHANGE or FLATLINE. A frozen sensor and one swinging wildly are
     * both WARNING, and need different fixes. NULL on records last written
     * before conditions were tracked -- unrecorded, not absent.
     */
    @Column(length = 20)
    private String condition;

    /** The last time this sensor produced a reading that passed every check. */
    @Column(name = "last_healthy_at")
    private LocalDateTime lastHealthyAt;

    /** When the value this sensor is currently stuck on first appeared; NULL unless flatlined. */
    @Column(name = "flatline_since")
    private LocalDateTime flatlineSince;

    @UpdateTimestamp
    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;
}
