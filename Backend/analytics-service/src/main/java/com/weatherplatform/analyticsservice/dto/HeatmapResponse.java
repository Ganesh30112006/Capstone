package com.weatherplatform.analyticsservice.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class HeatmapResponse {
    String parameter;
    LocalDateTime timestamp;
    List<HeatmapPointDTO> points;
}
