package com.weatherplatform.analyticsservice.entity;

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
@Table(name = "device_location_snapshots")
public class DeviceLocationSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, unique = true, length = 100)
    private String deviceUid;

    @Column(nullable = false)
    private Double latitude;

    @Column(nullable = false)
    private Double longitude;

    @Column(name = "latest_temperature", precision = 5, scale = 2)
    private BigDecimal latestTemperature;

    @Column(name = "latest_humidity", precision = 5, scale = 2)
    private BigDecimal latestHumidity;

    @Column(name = "latest_air_quality")
    private Integer latestAirQuality;

    @Column(name = "latest_pressure", precision = 7, scale = 2)
    private BigDecimal latestPressure;

    @Column(name = "latest_oxygen", precision = 5, scale = 2)
    private BigDecimal latestOxygen;

    @UpdateTimestamp
    @Column(name = "last_updated_at")
    private LocalDateTime lastUpdatedAt;
}
