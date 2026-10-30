package com.weatherplatform.deviceservice.repository;

import com.weatherplatform.deviceservice.entity.DeviceDigitalTwin;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface DeviceDigitalTwinRepository extends JpaRepository<DeviceDigitalTwin, Long> {
    Optional<DeviceDigitalTwin> findByDeviceUid(String deviceUid);
}
