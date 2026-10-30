package com.weatherplatform.alertservice.dto;

import com.weatherplatform.alertservice.entity.AlertStatus;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AlertStatusUpdateRequest {

    @NotNull(message = "Status is required")
    private AlertStatus status;
}
