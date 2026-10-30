package com.weatherplatform.sensorservice.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SensorReadingEvent implements Serializable {
    private String deviceUid;
    private BigDecimal temperature;
    private BigDecimal humidity;
    private Integer airQuality;
    private BigDecimal pressure;
    private Boolean rain;
    private BigDecimal oxygen;
    private String temperatureStatus;
    private String humidityStatus;
    private String airQualityStatus;
    private String pressureStatus;
    private String rainStatus;
    private String oxygenStatus;
    private Double latitude;
    private Double longitude;
    private LocalDateTime recordedAt;
}
