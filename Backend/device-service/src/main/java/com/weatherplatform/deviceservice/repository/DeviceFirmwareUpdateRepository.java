package com.weatherplatform.deviceservice.repository;

import com.weatherplatform.deviceservice.entity.DeviceFirmwareUpdate;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface DeviceFirmwareUpdateRepository extends JpaRepository<DeviceFirmwareUpdate, Long> {
    List<DeviceFirmwareUpdate> findByDeviceUidOrderByStartedAtDesc(String deviceUid);
}
