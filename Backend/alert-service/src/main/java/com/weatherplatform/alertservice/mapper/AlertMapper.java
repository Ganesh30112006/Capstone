package com.weatherplatform.alertservice.mapper;

import com.weatherplatform.alertservice.dto.AlertResponse;
import com.weatherplatform.alertservice.dto.AlertRuleRequest;
import com.weatherplatform.alertservice.dto.AlertRuleResponse;
import com.weatherplatform.alertservice.entity.Alert;
import com.weatherplatform.alertservice.entity.AlertRule;
import org.springframework.stereotype.Component;

@Component
public class AlertMapper {

    public AlertRule toEntity(AlertRuleRequest request) {
        return AlertRule.builder()
                .deviceUid(request.getDeviceUid())
                .metricType(request.getMetricType())
                .conditionType(request.getConditionType())
                .threshold(request.getThreshold())
                .active(request.isActive())
                .build();
    }

    public AlertRuleResponse toResponse(AlertRule entity) {
        return AlertRuleResponse.builder()
                .id(entity.getId())
                .deviceUid(entity.getDeviceUid())
                .metricType(entity.getMetricType())
                .conditionType(entity.getConditionType())
                .threshold(entity.getThreshold())
                .active(entity.isActive())
                .createdAt(entity.getCreatedAt())
                .build();
    }

    public AlertResponse toResponse(Alert entity) {
        return AlertResponse.builder()
                .id(entity.getId())
                .deviceUid(entity.getDeviceUid())
                .ruleId(entity.getRuleId())
                .metricType(entity.getMetricType())
                .severity(entity.getSeverity())
                .message(entity.getMessage())
                .triggerValue(entity.getTriggerValue())
                .status(entity.getStatus())
                .sensorType(entity.getSensorType())
                .occurrenceCount(entity.getOccurrenceCount())
                .triggeredAt(entity.getTriggeredAt())
                .lastTriggeredAt(entity.getLastTriggeredAt())
                .resolvedAt(entity.getResolvedAt())
                .build();
    }
}
