package com.weatherplatform.deviceservice.entity;

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
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "devices", indexes = {
        // The offline sweep scans (status, last_seen_at) once a minute across the fleet.
        @Index(name = "idx_devices_status_last_seen", columnList = "status, last_seen_at"),
        // Map and public-portal queries filter on visibility, then read coordinates.
        @Index(name = "idx_devices_public_visible", columnList = "public_visible"),
        @Index(name = "idx_devices_coordinates", columnList = "latitude, longitude"),
        @Index(name = "idx_devices_thing_name", columnList = "thing_name")
})
public class Device {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_uid", nullable = false, unique = true, length = 100)
    private String deviceUid;

    @Column(name = "device_name", nullable = false, length = 150)
    private String deviceName;

    @Column(name = "location_name", nullable = false, length = 200)
    private String locationName;

    @Column
    private Double latitude;

    @Column
    private Double longitude;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DeviceStatus status;

    @Column(name = "battery_level")
    private Integer batteryLevel;

    @Column(name = "wifi_signal_strength")
    private Integer wifiSignalStrength;

    @Column(name = "firmware_version", length = 50)
    private String firmwareVersion;

    @Column(name = "thing_name", length = 150)
    private String thingName;

    @Column(name = "certificate_id", length = 100)
    private String certificateId;

    @Column(name = "certificate_arn", length = 255)
    private String certificateArn;

    @Column(name = "iot_connection_status", length = 50)
    private String iotConnectionStatus;

    /**
     * Whether this station appears in the unauthenticated public portal.
     *
     * Public consumers only ever receive the sanitised projection (name,
     * location, coordinates, status) — never battery, Wi-Fi, firmware, or IoT
     * identity, which stay behind admin authorization regardless of this flag.
     */
    @Builder.Default
    @Column(name = "public_visible", nullable = false)
    private Boolean publicVisible = true;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @CreationTimestamp
    @Column(name = "registered_at", nullable = false, updatable = false)
    private LocalDateTime registeredAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
