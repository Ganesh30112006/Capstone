package com.weatherplatform.analyticsservice.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;

@Value
@Builder
public class HeatmapPointDTO {
    String deviceUid;
    Double latitude;
    Double longitude;
    BigDecimal value;
}
