package com.weatherplatform.sensorservice.mapper;

import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import org.springframework.stereotype.Component;

@Component
public class SensorDataMapper {

    public SensorData toEntity(SensorDataRequest request) {
        return SensorData.builder()
                .messageId(request.getMessageId())
                .deviceUid(request.getDeviceUid())
                .temperature(request.getTemperature())
                .humidity(request.getHumidity())
                .airQuality(request.getAirQuality())
                .pressure(request.getPressure())
                .rain(request.getRain())
                .oxygen(request.getOxygen())
                .latitude(request.getLatitude())
                .longitude(request.getLongitude())
                .recordedAt(request.getRecordedAt())
                .build();
    }

    public SensorDataResponse toResponse(SensorData entity) {
        return SensorDataResponse.builder()
                .id(entity.getId())
                .deviceUid(entity.getDeviceUid())
                .temperature(entity.getTemperature())
                .humidity(entity.getHumidity())
                .airQuality(entity.getAirQuality())
                .pressure(entity.getPressure())
                .rain(entity.getRain())
                .oxygen(entity.getOxygen())
                .latitude(entity.getLatitude())
                .longitude(entity.getLongitude())
                .recordedAt(entity.getRecordedAt())
                .createdAt(entity.getCreatedAt())
                .rawTemperature(entity.getRawTemperature())
                .rawHumidity(entity.getRawHumidity())
                .rawAirQuality(entity.getRawAirQuality())
                .rawPressure(entity.getRawPressure())
                .rawOxygen(entity.getRawOxygen())
                .build();
    }
}
