package com.weatherplatform.sensorservice.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
@Table(name = "sensor_data", indexes = {
        // Every read path is "one device, newest first" — latest, history, and
        // the failure detector's recent-readings window.
        @Index(name = "idx_sensor_data_device_recorded", columnList = "device_uid, recorded_at DESC"),
        @Index(name = "idx_sensor_data_recorded_at", columnList = "recorded_at")
})
public class SensorData {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * The producer-assigned ID from the original MQTT payload.
     *
     * Unique, so a redelivered SQS message or a RabbitMQ retry can only ever
     * insert a reading once. This is the platform's idempotency key: unlike an
     * in-memory cache it survives restarts and works across replicas.
     */
    @Column(name = "message_id", unique = true, length = 100)
    private String messageId;

    @Column(name = "device_uid", nullable = false, length = 100)
    private String deviceUid;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal temperature;

    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "air_quality", nullable = false)
    private Integer airQuality;

    @Column(nullable = false, precision = 7, scale = 2)
    private BigDecimal pressure;

    @Column(nullable = false)
    private Boolean rain;

    @Column(precision = 5, scale = 2)
    private BigDecimal oxygen;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Column(name = "recorded_at", nullable = false)
    private LocalDateTime recordedAt;

    /*
     * The device's own figures, before any calibration correction.
     *
     * The columns above hold the platform's best value for each quantity, which
     * is the corrected one; these keep what the sensor actually reported. NULL
     * on rows written before calibration existed -- for those the main column
     * already is the raw value, which is what the sensed*() accessors resolve.
     */
    @Column(name = "raw_temperature", precision = 5, scale = 2)
    private BigDecimal rawTemperature;

    @Column(name = "raw_humidity", precision = 5, scale = 2)
    private BigDecimal rawHumidity;

    @Column(name = "raw_air_quality")
    private Integer rawAirQuality;

    @Column(name = "raw_pressure", precision = 7, scale = 2)
    private BigDecimal rawPressure;

    @Column(name = "raw_oxygen", precision = 5, scale = 2)
    private BigDecimal rawOxygen;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    /*
     * What the sensor itself reported, for judging the sensor rather than the
     * environment. Health checks compare readings over time, and comparing
     * corrected values would make a new calibration look like a sudden jump.
     */
    public BigDecimal sensedTemperature() { return rawTemperature != null ? rawTemperature : temperature; }
    public BigDecimal sensedHumidity()    { return rawHumidity != null ? rawHumidity : humidity; }
    public Integer sensedAirQuality()     { return rawAirQuality != null ? rawAirQuality : airQuality; }
    public BigDecimal sensedPressure()    { return rawPressure != null ? rawPressure : pressure; }
    public BigDecimal sensedOxygen()      { return rawOxygen != null ? rawOxygen : oxygen; }
}
