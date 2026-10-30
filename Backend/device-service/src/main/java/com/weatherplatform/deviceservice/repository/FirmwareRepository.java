package com.weatherplatform.deviceservice.repository;

import com.weatherplatform.deviceservice.entity.Firmware;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface FirmwareRepository extends JpaRepository<Firmware, Long> {
    Optional<Firmware> findByVersion(String version);
    boolean existsByVersion(String version);
}
