package com.weatherplatform.sensorservice.calibration;

import com.weatherplatform.sensorservice.config.CalibrationProperties;
import com.weatherplatform.sensorservice.dto.CalibrationRequest;
import com.weatherplatform.sensorservice.dto.CalibrationResponse;
import com.weatherplatform.sensorservice.entity.SensorCalibration;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.exception.CalibrationRejectedException;
import com.weatherplatform.sensorservice.exception.ResourceNotFoundException;
import com.weatherplatform.sensorservice.repository.SensorCalibrationRepository;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CalibrationServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);
    private static final String DEVICE = "ESP32-CHN-001";

    @Mock
    private SensorCalibrationRepository calibrationRepository;
    @Mock
    private SensorDataRepository dataRepository;

    private CalibrationService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new CalibrationService(calibrationRepository, dataRepository, new CalibrationProperties(), clock);
        lenient().when(calibrationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    private void latestReading(SensorData reading) {
        when(dataRepository.findTopByDeviceUidOrderByRecordedAtDesc(DEVICE)).thenReturn(Optional.of(reading));
    }

    private static SensorData reading(LocalDateTime at) {
        return SensorData.builder()
                .deviceUid(DEVICE)
                .temperature(new BigDecimal("25.30"))
                .rawTemperature(new BigDecimal("25.18"))
                .humidity(new BigDecimal("60.00"))
                .airQuality(40)
                .pressure(new BigDecimal("1012.00"))
                .recordedAt(at)
                .build();
    }

    private static CalibrationRequest reference(String sensorType, String referenceValue) {
        return CalibrationRequest.builder().sensorType(sensorType).referenceValue(new BigDecimal(referenceValue)).build();
    }

    @Test
    void pairsTheReferenceWithTheSensorsRawReadingNotItsCorrectedOne() {
        // The stored temperature is already corrected to 25.30. Calibrating
        // against that would compute the correction on top of a correction.
        latestReading(reading(NOW.minusSeconds(20)));

        CalibrationResponse saved = service.record(DEVICE, reference("temperature", "25.40"), "ops@example.com");

        assertThat(saved.getSensorValue()).isEqualByComparingTo("25.18");
        assertThat(saved.getOffset()).isEqualByComparingTo("0.22");
        assertThat(saved.getPerformedBy()).isEqualTo("ops@example.com");
    }

    @Test
    void usesTheExplicitSensorValueWhenOneIsGiven() {
        latestReading(reading(NOW.minusSeconds(20)));
        CalibrationRequest request = reference("temperature", "25.40");
        request.setSensorValue(new BigDecimal("25.50"));

        assertThat(service.record(DEVICE, request, null).getOffset()).isEqualByComparingTo("-0.10");
    }

    @Test
    void setsValidityFromTheSensorTypesPolicy() {
        latestReading(reading(NOW.minusSeconds(20)));

        CalibrationResponse temperature = service.record(DEVICE, reference("temperature", "25.40"), null);
        CalibrationResponse airQuality = service.record(DEVICE, reference("airQuality", "42"), null);

        assertThat(temperature.getValidUntil()).isEqualTo(NOW.plusDays(180));
        assertThat(airQuality.getValidUntil()).isEqualTo(NOW.plusDays(90));
        assertThat(temperature.getStatus()).isEqualTo(CalibrationStatus.VALID);
    }

    @Test
    void refusesASensorWithNothingToOffset() {
        assertThatThrownBy(() -> service.record(DEVICE, reference("rain", "1"), null))
                .isInstanceOf(CalibrationRejectedException.class)
                .hasMessageContaining("cannot be calibrated");
        verify(calibrationRepository, never()).save(any());
    }

    @Test
    void reportsADeviceThatHasNeverReportedAsAbsent() {
        when(dataRepository.findTopByDeviceUidOrderByRecordedAtDesc(DEVICE)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.record(DEVICE, reference("temperature", "25"), null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void refusesToPairWithAReadingThatIsNotRecent() {
        latestReading(reading(NOW.minusMinutes(6)));

        assertThatThrownBy(() -> service.record(DEVICE, reference("temperature", "25.40"), null))
                .isInstanceOf(CalibrationRejectedException.class)
                .hasMessageContaining("360 seconds old");
    }

    @Test
    void acceptsAReadingRightAtTheRecencyLimit() {
        latestReading(reading(NOW.minusSeconds(300)));

        assertThat(service.record(DEVICE, reference("temperature", "25.40"), null)).isNotNull();
    }

    @Test
    void refusesAnOffsetBeyondThePlausibleLimit() {
        // A 10 degree correction is a failed sensor or a typo, not drift.
        latestReading(reading(NOW.minusSeconds(20)));

        assertThatThrownBy(() -> service.record(DEVICE, reference("temperature", "35.18"), null))
                .isInstanceOf(CalibrationRejectedException.class)
                .hasMessageContaining("exceeds the 5 limit");
        verify(calibrationRepository, never()).save(any());
    }

    @Test
    void acceptsAnOffsetExactlyAtTheLimit() {
        latestReading(reading(NOW.minusSeconds(20)));

        assertThat(service.record(DEVICE, reference("temperature", "30.18"), null).getOffset())
                .isEqualByComparingTo("5.00");
    }

    @Test
    void requiresTheSensorValueWhenRecordingAPastCalibration() {
        latestReading(reading(NOW.minusSeconds(20)));
        CalibrationRequest backdated = reference("temperature", "25.40");
        backdated.setCalibratedAt(NOW.minusDays(30));

        assertThatThrownBy(() -> service.record(DEVICE, backdated, null))
                .isInstanceOf(CalibrationRejectedException.class)
                .hasMessageContaining("sensorValue is required");
    }

    @Test
    void recordsAPastCalibrationWithItsOwnDates() {
        latestReading(reading(NOW.minusSeconds(20)));
        CalibrationRequest backdated = reference("temperature", "25.40");
        backdated.setSensorValue(new BigDecimal("25.30"));
        backdated.setCalibratedAt(NOW.minusDays(200));

        CalibrationResponse saved = service.record(DEVICE, backdated, null);

        assertThat(saved.getCalibratedAt()).isEqualTo(NOW.minusDays(200));
        assertThat(saved.getStatus()).isEqualTo(CalibrationStatus.EXPIRED);
    }

    @Test
    void refusesACalibrationDatedInTheFuture() {
        latestReading(reading(NOW.minusSeconds(20)));
        CalibrationRequest future = reference("temperature", "25.40");
        future.setSensorValue(new BigDecimal("25.30"));
        future.setCalibratedAt(NOW.plusMinutes(5));

        assertThatThrownBy(() -> service.record(DEVICE, future, null))
                .isInstanceOf(CalibrationRejectedException.class)
                .hasMessageContaining("in the future");
    }

    @Test
    void marksEveryCalibrationButTheNewestPerSensorAsSuperseded() {
        when(calibrationRepository.findByDeviceUidOrderByCalibratedAtDescIdDesc(DEVICE)).thenReturn(List.of(
                stored("temperature", NOW.minusDays(5)),
                stored("humidity", NOW.minusDays(8)),
                stored("temperature", NOW.minusDays(40))));

        List<CalibrationResponse> history = service.history(DEVICE);

        assertThat(history).extracting(CalibrationResponse::getStatus).containsExactly(
                CalibrationStatus.VALID, CalibrationStatus.VALID, CalibrationStatus.SUPERSEDED);
    }

    @Test
    void appliesOffsetsAndKeepsTheRawValuesBesideThem() {
        SensorData incoming = SensorData.builder()
                .temperature(new BigDecimal("25.18"))
                .humidity(new BigDecimal("60.00"))
                .airQuality(40)
                .pressure(new BigDecimal("1012.00"))
                .build();

        CalibrationService.correct(incoming, Map.of(
                "temperature", new BigDecimal("0.120"),
                "airQuality", new BigDecimal("-3.000")));

        assertThat(incoming.getTemperature()).isEqualByComparingTo("25.30");
        assertThat(incoming.getRawTemperature()).isEqualByComparingTo("25.18");
        assertThat(incoming.getAirQuality()).isEqualTo(37);
        assertThat(incoming.getRawAirQuality()).isEqualTo(40);
        // No calibration: unchanged, but the raw column is still written, because
        // NULL there is reserved for rows that predate calibration.
        assertThat(incoming.getHumidity()).isEqualByComparingTo("60.00");
        assertThat(incoming.getRawHumidity()).isEqualByComparingTo("60.00");
        assertThat(incoming.getOxygen()).isNull();
        assertThat(incoming.getRawOxygen()).isNull();
    }

    @Test
    void correctsWithTheCalibrationInForceWhenTheReadingWasTaken() {
        SensorData late = SensorData.builder().deviceUid(DEVICE).temperature(new BigDecimal("20.00"))
                .humidity(new BigDecimal("50.00")).airQuality(10).pressure(new BigDecimal("1000.00"))
                .recordedAt(NOW.minusHours(3)).build();
        when(calibrationRepository.findActiveForDevice(DEVICE, NOW.minusHours(3))).thenReturn(List.of(
                SensorCalibration.builder().sensorType("temperature").offsetValue(new BigDecimal("0.500")).build()));

        service.applyCorrections(late);

        ArgumentCaptor<LocalDateTime> asOf = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(calibrationRepository).findActiveForDevice(any(), asOf.capture());
        assertThat(asOf.getValue()).isEqualTo(NOW.minusHours(3));
        assertThat(late.getTemperature()).isEqualByComparingTo("20.50");
    }

    private static SensorCalibration stored(String sensorType, LocalDateTime at) {
        return SensorCalibration.builder()
                .deviceUid(DEVICE).sensorType(sensorType)
                .referenceValue(BigDecimal.ONE).sensorValue(BigDecimal.ONE).offsetValue(BigDecimal.ZERO)
                .calibratedAt(at).validUntil(at.plusDays(180))
                .build();
    }
}
