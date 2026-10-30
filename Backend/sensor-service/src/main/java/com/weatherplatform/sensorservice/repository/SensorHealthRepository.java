package com.weatherplatform.sensorservice.repository;

import com.weatherplatform.sensorservice.entity.SensorHealth;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface SensorHealthRepository extends JpaRepository<SensorHealth, Long> {
    List<SensorHealth> findByDeviceUid(String deviceUid);

    /**
     * Every health record for a set of devices, in one query.
     *
     * The admin console used to ask for diagnostics one station at a time, so
     * drawing the sensor page cost a round trip per station. Grouping by
     * deviceUid happens in the caller; the point of this method is that the
     * database is asked once.
     */
    List<SensorHealth> findByDeviceUidIn(Collection<String> deviceUids);
    Optional<SensorHealth> findByDeviceUidAndSensorType(String deviceUid, String sensorType);
}
