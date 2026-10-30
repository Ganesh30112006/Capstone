package com.weatherplatform.sensorservice.repository;

import com.weatherplatform.sensorservice.entity.SensorData;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface SensorDataRepository extends JpaRepository<SensorData, Long> {

    /** Idempotency check: has this exact MQTT message already been stored? */
    boolean existsByMessageId(String messageId);

    java.util.Optional<SensorData> findByMessageId(String messageId);


    Optional<SensorData> findTopByDeviceUidOrderByRecordedAtDesc(String deviceUid);

    /**
     * The latest row for each of several devices, in one query.
     *
     * Rendering the fleet needs the most recent reading per station, and the
     * only way to get it was one request per device: the console issued
     * `GET /devices` and then `GET /sensors/{uid}/latest` N times, so a
     * 500-station deployment cost 501 round trips through the gateway for a
     * single page, on six different pages.
     *
     * DISTINCT ON is PostgreSQL's native "first row per group": ordering by
     * (device_uid, recorded_at DESC) and keeping the first row of each
     * device_uid group walks idx_sensor_data_device_recorded directly, so this
     * reads one index entry per requested device rather than scanning history.
     */
    @Query(value = """
            SELECT DISTINCT ON (device_uid) *
            FROM sensor_data
            WHERE device_uid IN (:deviceUids)
            ORDER BY device_uid, recorded_at DESC
            """, nativeQuery = true)
    List<SensorData> findLatestForEach(@Param("deviceUids") Collection<String> deviceUids);

    Page<SensorData> findByDeviceUidOrderByRecordedAtDesc(String deviceUid, Pageable pageable);

    Page<SensorData> findByDeviceUidAndRecordedAtBetweenOrderByRecordedAtDesc(
            String deviceUid, LocalDateTime startDate, LocalDateTime endDate, Pageable pageable);

    long countByDeviceUid(String deviceUid);

    @Query(value = "SELECT COUNT(*) as totalReadings, " +
            "AVG(temperature) as avgTemperature, MIN(temperature) as minTemperature, MAX(temperature) as maxTemperature, " +
            "AVG(humidity) as avgHumidity, MIN(humidity) as minHumidity, MAX(humidity) as maxHumidity, " +
            "AVG(air_quality) as avgAirQuality, MIN(air_quality) as minAirQuality, MAX(air_quality) as maxAirQuality, " +
            "AVG(pressure) as avgPressure, MIN(pressure) as minPressure, MAX(pressure) as maxPressure " +
            "FROM sensor_data WHERE device_uid = :deviceUid", nativeQuery = true)
    SensorStatisticsProjection getStatisticsByDeviceUid(@Param("deviceUid") String deviceUid);

    @Modifying
    @Query("DELETE FROM SensorData s WHERE s.recordedAt < :cutoff")
    int deleteByRecordedAtBefore(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Projection interface for aggregate statistics query.
     */
    interface SensorStatisticsProjection {
        Long getTotalReadings();
        Double getAvgTemperature();
        java.math.BigDecimal getMinTemperature();
        java.math.BigDecimal getMaxTemperature();
        Double getAvgHumidity();
        java.math.BigDecimal getMinHumidity();
        java.math.BigDecimal getMaxHumidity();
        Double getAvgAirQuality();
        Integer getMinAirQuality();
        Integer getMaxAirQuality();
        Double getAvgPressure();
        java.math.BigDecimal getMinPressure();
        java.math.BigDecimal getMaxPressure();
    }
}
