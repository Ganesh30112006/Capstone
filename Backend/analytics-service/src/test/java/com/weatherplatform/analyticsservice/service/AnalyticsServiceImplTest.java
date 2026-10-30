package com.weatherplatform.analyticsservice.service;

import com.weatherplatform.analyticsservice.dto.DailyStatisticsResponse;
import com.weatherplatform.analyticsservice.entity.DailyStatistics;
import com.weatherplatform.analyticsservice.mapper.StatisticsMapper;
import com.weatherplatform.analyticsservice.repository.DailyStatisticsRepository;
import com.weatherplatform.analyticsservice.repository.DeviceLocationSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnalyticsServiceImplTest {

    @Mock
    private DailyStatisticsRepository repository;
    @Mock
    private DeviceLocationSnapshotRepository snapshotRepository;
    @Mock
    private StatisticsMapper mapper;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    @Test
    void getWeeklyStatistics_NoData_ReturnsEmptyList() {
        when(repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(
                eq("DEV-1"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        List<DailyStatisticsResponse> result = analyticsService.getWeeklyStatistics("DEV-1");

        assertTrue(result.isEmpty());
    }

    @Test
    void getMonthlyStatistics_NoData_ReturnsEmptyList() {
        when(repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(
                eq("DEV-1"), any(LocalDate.class), any(LocalDate.class)))
                .thenReturn(Collections.emptyList());

        assertTrue(analyticsService.getMonthlyStatistics("DEV-1").isEmpty());
    }

    @Test
    void getDailyStatistics_WithData_MapsEntitiesThroughMapper() {
        LocalDate from = LocalDate.of(2024, 1, 1);
        LocalDate to = LocalDate.of(2024, 1, 7);

        DailyStatistics entity = mock(DailyStatistics.class);
        DailyStatisticsResponse response = DailyStatisticsResponse.builder()
                .deviceUid("DEV-1")
                .date(from)
                .readingCount(10)
                .build();

        when(repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc("DEV-1", from, to))
                .thenReturn(List.of(entity));
        when(mapper.toResponse(entity)).thenReturn(response);

        List<DailyStatisticsResponse> result = analyticsService.getDailyStatistics("DEV-1", from, to);

        assertEquals(1, result.size());
        assertSame(response, result.get(0));
    }
}
