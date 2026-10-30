package com.weatherplatform.deviceservice.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "device_digital_twins")
public class DeviceDigitalTwin {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, unique = true, length = 100)
    private String deviceUid;

    @Column(precision = 5, scale = 2)
    private BigDecimal temperature;

    /*
     * Each probe starts UNKNOWN: a twin is created before any reading has been
     * checked, and starting at HEALTHY reported a passed check that never ran.
     */
    @Column(name = "temperature_status", length = 20)
    @Builder.Default
    private String temperatureStatus = "UNKNOWN";

    @Column(precision = 5, scale = 2)
    private BigDecimal humidity;

    @Column(name = "humidity_status", length = 20)
    @Builder.Default
    private String humidityStatus = "UNKNOWN";

    @Column(name = "air_quality")
    private Integer airQuality;

    @Column(name = "air_quality_status", length = 20)
    @Builder.Default
    private String airQualityStatus = "UNKNOWN";

    @Column(precision = 7, scale = 2)
    private BigDecimal pressure;

    @Column(name = "pressure_status", length = 20)
    @Builder.Default
    private String pressureStatus = "UNKNOWN";

    @Column
    private Boolean rain;

    @Column(name = "rain_status", length = 20)
    @Builder.Default
    private String rainStatus = "UNKNOWN";

    @Column(precision = 5, scale = 2)
    private BigDecimal oxygen;

    @Column(name = "oxygen_status", length = 20)
    @Builder.Default
    private String oxygenStatus = "UNKNOWN";
    
    // AWS IoT Shadow Alignment Fields
    @Column(name = "connection_status", length = 20)
    private String connectionStatus;

    @Column(name = "battery_level")
    private Integer batteryLevel;

    @Column(name = "wifi_signal_strength")
    private Integer wifiSignalStrength;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "last_latitude")
    private Double lastLatitude;

    @Column(name = "last_longitude")
    private Double lastLongitude;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @UpdateTimestamp
    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;
}
