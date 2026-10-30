package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.dto.PagedSensorDataResponse;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.dto.SensorDataResponse;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.exception.InvalidDateRangeException;
import com.weatherplatform.sensorservice.exception.ResourceNotFoundException;
import com.weatherplatform.sensorservice.mapper.SensorDataMapper;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorDataServiceImplTest {

    @Mock
    private SensorDataRepository sensorDataRepository;

    @Mock
    private SensorDataMapper sensorDataMapper;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private SensorFailureDetector failureDetector;

    @Mock
    private com.weatherplatform.sensorservice.calibration.CalibrationService calibrationService;

    @Mock
    private com.weatherplatform.sensorservice.calibration.SensorStatusService sensorStatusService;


    @InjectMocks
    private SensorDataServiceImpl sensorDataService;

    private SensorDataRequest sampleRequest;
    private SensorData sampleEntity;
    private SensorDataResponse sampleResponse;
    private LocalDateTime now;

    @BeforeEach
    void setUp() {
        now = LocalDateTime.now();

        sampleRequest = SensorDataRequest.builder()
                .deviceUid("ESP32-TEST-001")
                .temperature(new BigDecimal("25.50"))
                .humidity(new BigDecimal("60.00"))
                .airQuality(45)
                .pressure(new BigDecimal("1013.25"))
                .rain(false)
                .recordedAt(now)
                .build();

        sampleEntity = SensorData.builder()
                .id(1L)
                .deviceUid("ESP32-TEST-001")
                .temperature(new BigDecimal("25.50"))
                .humidity(new BigDecimal("60.00"))
                .airQuality(45)
                .pressure(new BigDecimal("1013.25"))
                .rain(false)
                .recordedAt(now)
                .createdAt(now)
                .build();

        sampleResponse = SensorDataResponse.builder()
                .id(1L)
                .deviceUid("ESP32-TEST-001")
                .temperature(new BigDecimal("25.50"))
                .humidity(new BigDecimal("60.00"))
                .airQuality(45)
                .pressure(new BigDecimal("1013.25"))
                .rain(false)
                .recordedAt(now)
                .createdAt(now)
                .build();
    }

    @Test
    @DisplayName("storeSensorData - Success case")
    void storeSensorData_Success() {
        when(sensorDataMapper.toEntity(sampleRequest)).thenReturn(sampleEntity);
        when(sensorDataRepository.saveAndFlush(sampleEntity)).thenReturn(sampleEntity);
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);

        SensorDataResponse result = sensorDataService.storeSensorData(sampleRequest);

        assertThat(result).isNotNull();
        assertThat(result.getDeviceUid()).isEqualTo("ESP32-TEST-001");
        assertThat(result.getTemperature()).isEqualTo(new BigDecimal("25.50"));
        verify(sensorDataRepository).saveAndFlush(sampleEntity);
    }

    @Test
    @DisplayName("storeSensorData - a replayed messageId is stored only once")
    void storeSensorData_DuplicateMessageId_IsNotStoredTwice() {
        sampleRequest.setMessageId("msg-replay-1");
        sampleEntity.setMessageId("msg-replay-1");

        when(sensorDataRepository.existsByMessageId("msg-replay-1")).thenReturn(true);
        when(sensorDataRepository.findByMessageId("msg-replay-1")).thenReturn(Optional.of(sampleEntity));
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);

        SensorDataResponse result = sensorDataService.storeSensorData(sampleRequest);

        // The caller still gets the reading back, but nothing is written and no
        // duplicate event reaches analytics or alerting.
        assertThat(result).isNotNull();
        assertThat(result.getDeviceUid()).isEqualTo("ESP32-TEST-001");
        verify(sensorDataRepository, never()).saveAndFlush(any(SensorData.class));
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("storeSensorData - losing the insert race is treated as already processed")
    void storeSensorData_ConcurrentDuplicate_IsTreatedAsProcessed() {
        sampleRequest.setMessageId("msg-race-1");
        sampleEntity.setMessageId("msg-race-1");

        when(sensorDataRepository.existsByMessageId("msg-race-1")).thenReturn(false);
        when(sensorDataMapper.toEntity(sampleRequest)).thenReturn(sampleEntity);
        when(sensorDataRepository.saveAndFlush(sampleEntity))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));
        when(sensorDataRepository.findByMessageId("msg-race-1")).thenReturn(Optional.of(sampleEntity));
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);

        SensorDataResponse result = sensorDataService.storeSensorData(sampleRequest);

        assertThat(result).isNotNull();
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    @DisplayName("storeSensorData - a failed event publish does not fail the write")
    void storeSensorData_PublishFailure_DoesNotRollBackStorage() {
        when(sensorDataMapper.toEntity(sampleRequest)).thenReturn(sampleEntity);
        when(sensorDataRepository.saveAndFlush(sampleEntity)).thenReturn(sampleEntity);
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);
        doThrow(new AmqpException("broker unavailable"))
                .when(rabbitTemplate).convertAndSend(anyString(), anyString(), any(Object.class));

        // Telemetry durability must not depend on the broker being reachable.
        SensorDataResponse result = sensorDataService.storeSensorData(sampleRequest);

        assertThat(result).isNotNull();
        verify(sensorDataRepository).saveAndFlush(sampleEntity);
    }

    @Test
    @DisplayName("getLatestByDeviceUid - Found case")
    void getLatestByDeviceUid_Found() {
        when(sensorDataRepository.findTopByDeviceUidOrderByRecordedAtDesc("ESP32-TEST-001"))
                .thenReturn(Optional.of(sampleEntity));
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);

        SensorDataResponse result = sensorDataService.getLatestByDeviceUid("ESP32-TEST-001");

        assertThat(result).isNotNull();
        assertThat(result.getDeviceUid()).isEqualTo("ESP32-TEST-001");
        verify(sensorDataRepository).findTopByDeviceUidOrderByRecordedAtDesc("ESP32-TEST-001");
    }

    @Test
    @DisplayName("getLatestByDeviceUid - NotFound throws ResourceNotFoundException")
    void getLatestByDeviceUid_NotFound() {
        when(sensorDataRepository.findTopByDeviceUidOrderByRecordedAtDesc("UNKNOWN-UID"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> sensorDataService.getLatestByDeviceUid("UNKNOWN-UID"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("No sensor data found for device: UNKNOWN-UID");
    }

    @Test
    @DisplayName("getHistoryByDeviceUid - Paginated success case")
    void getHistoryByDeviceUid_Success() {
        Page<SensorData> page = new PageImpl<>(List.of(sampleEntity), PageRequest.of(0, 10), 1);
        when(sensorDataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("ESP32-TEST-001"), any(Pageable.class)))
                .thenReturn(page);
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);

        PagedSensorDataResponse response = sensorDataService.getHistoryByDeviceUid("ESP32-TEST-001", 0, 10);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
        assertThat(response.getTotalElements()).isEqualTo(1);
        assertThat(response.getPageNumber()).isZero();
    }

    @Test
    @DisplayName("getHistoryByDeviceUidBetweenDates - Success case")
    void getHistoryBetweenDates_Success() {
        LocalDateTime start = now.minusDays(1);
        LocalDateTime end = now.plusDays(1);

        Page<SensorData> page = new PageImpl<>(List.of(sampleEntity), PageRequest.of(0, 10), 1);
        when(sensorDataRepository.findByDeviceUidAndRecordedAtBetweenOrderByRecordedAtDesc(
                eq("ESP32-TEST-001"), eq(start), eq(end), any(Pageable.class)))
                .thenReturn(page);
        when(sensorDataMapper.toResponse(sampleEntity)).thenReturn(sampleResponse);

        PagedSensorDataResponse response = sensorDataService.getHistoryByDeviceUidBetweenDates(
                "ESP32-TEST-001", start, end, 0, 10);

        assertThat(response).isNotNull();
        assertThat(response.getContent()).hasSize(1);
    }

    @Test
    @DisplayName("getHistoryByDeviceUidBetweenDates - Invalid date range throws exception")
    void getHistoryBetweenDates_InvalidRange() {
        LocalDateTime start = now.plusDays(1);
        LocalDateTime end = now.minusDays(1);

        assertThatThrownBy(() -> sensorDataService.getHistoryByDeviceUidBetweenDates(
                "ESP32-TEST-001", start, end, 0, 10))
                .isInstanceOf(InvalidDateRangeException.class)
                .hasMessageContaining("startDate");

        verify(sensorDataRepository, never())
                .findByDeviceUidAndRecordedAtBetweenOrderByRecordedAtDesc(any(), any(), any(), any());
    }
}
