package com.weatherplatform.sensorservice.repository;

import com.weatherplatform.sensorservice.entity.SensorCalibration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

@Repository
public interface SensorCalibrationRepository extends JpaRepository<SensorCalibration, Long> {

    /** A device's full calibration history, newest first. */
    List<SensorCalibration> findByDeviceUidOrderByCalibratedAtDescIdDesc(String deviceUid);

    /**
     * The calibration in force for each of a device's sensors: the newest one
     * dated at or before {@code asOf}.
     *
     * Runs once per stored reading, so it is one indexed query for all of the
     * device's sensors rather than one per sensor. A calibration dated after the
     * reading was taken does not apply to it.
     */
    @Query(value = """
            SELECT DISTINCT ON (sensor_type) *
              FROM sensor_calibration
             WHERE device_uid = :deviceUid
               AND calibrated_at <= :asOf
             ORDER BY sensor_type, calibrated_at DESC, id DESC
            """, nativeQuery = true)
    List<SensorCalibration> findActiveForDevice(@Param("deviceUid") String deviceUid,
                                                @Param("asOf") LocalDateTime asOf);

    /**
     * The two newest calibrations of every sensor on the given devices -- the
     * current one, and the one before it that drift is measured against.
     */
    @Query(value = """
            SELECT *
              FROM (SELECT c.*,
                           ROW_NUMBER() OVER (PARTITION BY device_uid, sensor_type
                                              ORDER BY calibrated_at DESC, id DESC) AS rn
                      FROM sensor_calibration c
                     WHERE device_uid IN (:deviceUids)) ranked
             WHERE rn <= 2
             ORDER BY device_uid, sensor_type, calibrated_at DESC, id DESC
            """, nativeQuery = true)
    List<SensorCalibration> findLatestTwoForEach(@Param("deviceUids") Collection<String> deviceUids);
}
