package com.weatherplatform.deviceservice.repository;

import com.weatherplatform.deviceservice.entity.Device;
import com.weatherplatform.deviceservice.entity.DeviceStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<Device, Long> {
    Optional<Device> findByDeviceUid(String deviceUid);
    boolean existsByDeviceUid(String deviceUid);
    List<Device> findByStatus(DeviceStatus status);

    // Public-portal projections: only stations explicitly published are visible.
    List<Device> findByPublicVisibleTrue();
    Optional<Device> findByDeviceUidAndPublicVisibleTrue(String deviceUid);

    List<Device> findByStatusAndLastSeenAtBefore(DeviceStatus status, LocalDateTime threshold);

    @Modifying
    @Query("UPDATE Device d SET d.status = :offlineStatus WHERE d.status = :onlineStatus AND d.lastSeenAt < :threshold")
    int markDevicesOffline(@Param("onlineStatus") DeviceStatus onlineStatus, 
                           @Param("offlineStatus") DeviceStatus offlineStatus, 
                           @Param("threshold") LocalDateTime threshold);
}
