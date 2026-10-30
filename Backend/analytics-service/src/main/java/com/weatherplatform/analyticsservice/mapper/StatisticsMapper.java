package com.weatherplatform.analyticsservice.mapper;

import com.weatherplatform.analyticsservice.dto.DailyStatisticsResponse;
import com.weatherplatform.analyticsservice.entity.DailyStatistics;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;

@Component
public class StatisticsMapper {

    public DailyStatisticsResponse toResponse(DailyStatistics entity) {
        int count = entity.getReadingCount();
        return DailyStatisticsResponse.builder()
                .deviceUid(entity.getDeviceUid())
                .date(entity.getStatDate())
                .avgTemperature(divide(entity.getSumTemperature(), count))
                .minTemperature(entity.getMinTemperature())
                .maxTemperature(entity.getMaxTemperature())
                .avgHumidity(divide(entity.getSumHumidity(), count))
                .minHumidity(entity.getMinHumidity())
                .maxHumidity(entity.getMaxHumidity())
                .avgAirQuality(count > 0 && entity.getSumAirQuality() != null ? (double) entity.getSumAirQuality() / count : null)
                .minAirQuality(entity.getMinAirQuality())
                .maxAirQuality(entity.getMaxAirQuality())
                .avgPressure(divide(entity.getSumPressure(), count))
                .minPressure(entity.getMinPressure())
                .maxPressure(entity.getMaxPressure())
                .avgOxygen(divide(entity.getSumOxygen(), count))
                .minOxygen(entity.getMinOxygen())
                .maxOxygen(entity.getMaxOxygen())
                .rainEventsCount(entity.getRainEventsCount())
                .readingCount(count)
                .build();
    }

    private BigDecimal divide(BigDecimal sum, int count) {
        if (sum == null || count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
