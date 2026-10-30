package com.weatherplatform.alertservice.dto;

import com.weatherplatform.alertservice.entity.ConditionType;
import com.weatherplatform.alertservice.entity.MetricType;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
public class AlertRuleResponse {
    Long id;
    String deviceUid;
    MetricType metricType;
    ConditionType conditionType;
    BigDecimal threshold;
    boolean active;
    LocalDateTime createdAt;
}
