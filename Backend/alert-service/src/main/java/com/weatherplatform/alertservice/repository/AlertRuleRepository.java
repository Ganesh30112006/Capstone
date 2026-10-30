package com.weatherplatform.alertservice.repository;

import com.weatherplatform.alertservice.entity.AlertRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AlertRuleRepository extends JpaRepository<AlertRule, Long> {
    List<AlertRule> findByDeviceUid(String deviceUid);
    List<AlertRule> findByDeviceUidAndActiveTrue(String deviceUid);
}
