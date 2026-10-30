package com.weatherplatform.alertservice.dto;

import com.weatherplatform.alertservice.entity.ConditionType;
import com.weatherplatform.alertservice.entity.MetricType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertRuleRequest {

    @NotBlank(message = "deviceUid is required")
    private String deviceUid;

    @NotNull(message = "metricType is required")
    private MetricType metricType;

    @NotNull(message = "conditionType is required")
    private ConditionType conditionType;

    @NotNull(message = "threshold is required")
    private BigDecimal threshold;

    private boolean active = true;
}
