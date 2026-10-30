package com.weatherplatform.analyticsservice.entity;

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
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "daily_statistics", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"device_uid", "stat_date"})
})
public class DailyStatistics {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, length = 100)
    private String deviceUid;

    @Column(name = "stat_date", nullable = false)
    private LocalDate statDate;

    /*
     * The sum_* columns and rain_events_count are running counters, and a
     * counter is never unknown -- it is zero until incremented. V2 makes
     * that NOT NULL in the database; these annotations keep the entity
     * telling the same story, so `ddl-auto: validate` agrees with it.
     */
    @Column(name = "sum_temperature", precision = 12, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal sumTemperature = BigDecimal.ZERO;

    @Column(name = "min_temperature", precision = 5, scale = 2)
    private BigDecimal minTemperature;

    @Column(name = "max_temperature", precision = 5, scale = 2)
    private BigDecimal maxTemperature;

    @Column(name = "sum_humidity", precision = 12, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal sumHumidity = BigDecimal.ZERO;

    @Column(name = "min_humidity", precision = 5, scale = 2)
    private BigDecimal minHumidity;

    @Column(name = "max_humidity", precision = 5, scale = 2)
    private BigDecimal maxHumidity;

    @Column(name = "sum_air_quality", nullable = false)
    @Builder.Default
    private Long sumAirQuality = 0L;

    @Column(name = "min_air_quality")
    private Integer minAirQuality;

    @Column(name = "max_air_quality")
    private Integer maxAirQuality;

    @Column(name = "sum_pressure", precision = 14, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal sumPressure = BigDecimal.ZERO;

    @Column(name = "min_pressure", precision = 7, scale = 2)
    private BigDecimal minPressure;

    @Column(name = "max_pressure", precision = 7, scale = 2)
    private BigDecimal maxPressure;

    @Column(name = "sum_oxygen", precision = 12, scale = 2, nullable = false)
    @Builder.Default
    private BigDecimal sumOxygen = BigDecimal.ZERO;

    @Column(name = "min_oxygen", precision = 5, scale = 2)
    private BigDecimal minOxygen;

    @Column(name = "max_oxygen", precision = 5, scale = 2)
    private BigDecimal maxOxygen;

    // min_*/max_* stay nullable on purpose: an extreme over zero readings
    // genuinely has no value, and NULL is the honest representation.
    @Column(name = "rain_events_count", nullable = false)
    @Builder.Default
    private Integer rainEventsCount = 0;

    @Column(name = "reading_count", nullable = false)
    @Builder.Default
    private Integer readingCount = 0;

    /*
     * How many of this bucket's readings actually carried each metric.
     *
     * reading_count counts every reading the station sent, whichever metrics
     * were in it, so dividing a metric's sum by reading_count pulls the average
     * toward zero for any station that does not report that metric -- and makes
     * "never measured" indistinguishable from "measured zero".
     *
     * Nullable, and not defaulted: NULL means the count is unknown for a row
     * written before V3__per_metric_reading_counts, and the read path reports
     * unknown rather than inventing a divisor. Zero means the metric was
     * genuinely absent from every reading, which is a different statement.
     */
    @Column(name = "temperature_count")
    private Integer temperatureCount;

    @Column(name = "humidity_count")
    private Integer humidityCount;

    @Column(name = "air_quality_count")
    private Integer airQualityCount;

    @Column(name = "pressure_count")
    private Integer pressureCount;

    @Column(name = "oxygen_count")
    private Integer oxygenCount;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
