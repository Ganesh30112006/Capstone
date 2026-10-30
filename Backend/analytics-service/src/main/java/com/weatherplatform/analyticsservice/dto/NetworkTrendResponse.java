package com.weatherplatform.analyticsservice.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import java.math.BigDecimal;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NetworkTrendResponse {
    private String timestamp;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private Integer airQuality;
    private BigDecimal oxygen;
    private BigDecimal pressure;
}
