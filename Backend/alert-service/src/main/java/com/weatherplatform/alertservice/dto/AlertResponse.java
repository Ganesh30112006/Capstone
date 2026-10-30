package com.weatherplatform.alertservice.dto;

import com.weatherplatform.alertservice.entity.AlertSeverity;
import com.weatherplatform.alertservice.entity.AlertStatus;
import com.weatherplatform.alertservice.entity.MetricType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class AlertResponse {

    Long id;
    String deviceUid;
    Long ruleId;
    MetricType metricType;
    AlertSeverity severity;
    String message;
    BigDecimal triggerValue;
    AlertStatus status;

    /** Which sensor this concerns, when the alert is sensor-specific. */
    String sensorType;

    /**
     * How many times this condition recurred while the alert stayed open.
     *
     * Surfaced so the console can show "seen 47 times" rather than implying a
     * single blip — deduplication collapses the rows, not the signal.
     */
    Integer occurrenceCount;

    LocalDateTime triggeredAt;
    LocalDateTime lastTriggeredAt;
    LocalDateTime resolvedAt;
}
