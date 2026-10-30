package com.weatherplatform.sensorservice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Value
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SensorDataResponse {
    Long id;
    String deviceUid;
    BigDecimal temperature;
    BigDecimal humidity;
    Integer airQuality;
    BigDecimal pressure;
    Boolean rain;
    BigDecimal oxygen;
    Double latitude;
    Double longitude;
    LocalDateTime recordedAt;
    LocalDateTime createdAt;

    /*
     * The sensor's own figures before calibration; the fields above are the
     * corrected values. Omitted on readings stored before calibration existed,
     * whose main values are already the raw ones.
     */
    BigDecimal rawTemperature;
    BigDecimal rawHumidity;
    Integer rawAirQuality;
    BigDecimal rawPressure;
    BigDecimal rawOxygen;
}
