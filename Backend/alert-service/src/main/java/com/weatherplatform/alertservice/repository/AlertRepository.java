package com.weatherplatform.alertservice.repository;

import com.weatherplatform.alertservice.entity.Alert;
import com.weatherplatform.alertservice.entity.AlertStatus;
import com.weatherplatform.alertservice.entity.MetricType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AlertRepository extends JpaRepository<Alert, Long> {

    List<Alert> findByDeviceUidOrderByTriggeredAtDesc(String deviceUid);

    List<Alert> findByDeviceUidAndStatusOrderByTriggeredAtDesc(String deviceUid, AlertStatus status);

    List<Alert> findAllByOrderByTriggeredAtDesc();

    List<Alert> findByStatusOrderByTriggeredAtDesc(AlertStatus status);

    List<Alert> findByMetricTypeOrderByTriggeredAtDesc(MetricType metricType);

    /**
     * The open alert, if any, already representing this exact condition.
     *
     * Deduplication hinges on this lookup: while a matching ACTIVE alert exists,
     * a recurrence updates it rather than creating another row.
     */
    Optional<Alert> findFirstByDedupeKeyAndStatusOrderByTriggeredAtDesc(String dedupeKey, AlertStatus status);
}
