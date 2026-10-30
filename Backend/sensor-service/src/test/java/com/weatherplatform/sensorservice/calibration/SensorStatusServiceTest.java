package com.weatherplatform.sensorservice.calibration;

import com.weatherplatform.sensorservice.config.CalibrationProperties;
import com.weatherplatform.sensorservice.dto.SensorHealthResponse;
import com.weatherplatform.sensorservice.entity.SensorCalibration;
import com.weatherplatform.sensorservice.repository.SensorCalibrationRepository;
import com.weatherplatform.sensorservice.service.SensorFailureDetector;
import com.weatherplatform.sensorservice.service.SensorHealthLedger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SensorStatusServiceTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);

    @Mock
    private SensorFailureDetector detector;
    @Mock
    private SensorHealthLedger ledger;
    @Mock
    private SensorCalibrationRepository calibrationRepository;

    private SensorStatusService service;

    @BeforeEach
    void setUp() {
        service = new SensorStatusService(detector, ledger, calibrationRepository, new CalibrationProperties(),
                Clock.fixed(NOW.toInstant(ZoneOffset.UTC), ZoneOffset.UTC));
    }

    @Test
    void hasNoHealthScoreWithoutEvaluations() {
        assertThat(SensorStatusService.score(null)).isNull();
        assertThat(SensorStatusService.score(new SensorHealthLedger.Tally(0, 0, 0, NOW))).isNull();
    }

    @Test
    void scoresThePassRateWithTheCountsBehindIt() {
        SensorHealthResponse.HealthScore score = SensorStatusService.score(new SensorHealthLedger.Tally(98, 1, 1, NOW));

        assertThat(score.getPercent()).isEqualByComparingTo("98.0");
        assertThat(score.getPassed()).isEqualTo(98);
        assertThat(score.getFailed()).isEqualTo(1);
    }

    @Test
    void neverRoundsAFailingSensorUpToAPerfectScore() {
        assertThat(SensorStatusService.score(new SensorHealthLedger.Tally(2999, 0, 1, NOW)).getPercent())
                .isEqualByComparingTo("99.9");
        assertThat(SensorStatusService.score(new SensorHealthLedger.Tally(3000, 0, 0, NOW)).getPercent())
                .isEqualByComparingTo("100.0");
    }

    @Test
    void summarisesAnUncalibratedSensorWithNothingButItsStatus() {
        SensorHealthResponse.CalibrationSummary summary = service.summarise(List.of(), NOW);

        assertThat(summary.getStatus()).isEqualTo(CalibrationStatus.NOT_CALIBRATED);
        assertThat(summary.getOffset()).isNull();
        assertThat(summary.getDriftEstimate()).isNull();
    }

    @Test
    void namesTheBaselineADriftEstimateWasMeasuredFrom() {
        SensorHealthResponse.CalibrationSummary summary = service.summarise(List.of(
                calibration("-0.200", NOW.minusDays(6)),
                calibration("0.000", NOW.minusDays(16))), NOW);

        assertThat(summary.getStatus()).isEqualTo(CalibrationStatus.VALID);
        assertThat(summary.getOffset()).isEqualByComparingTo("-0.200");
        assertThat(summary.getDriftEstimate()).isEqualByComparingTo("0.120");
        assertThat(summary.getDriftBaselineFrom()).isEqualTo(NOW.minusDays(16));
    }

    @Test
    void namesNoBaselineWhenThereIsNoEstimate() {
        SensorHealthResponse.CalibrationSummary summary = service.summarise(
                List.of(calibration("-0.200", NOW.minusDays(6))), NOW);

        assertThat(summary.getDriftEstimate()).isNull();
        assertThat(summary.getDriftBaselineFrom()).isNull();
    }

    @Test
    void attachesScoresAndCalibrationToTheDetectorsReport() {
        when(detector.getSensorHealthForEach(anyCollection())).thenReturn(Map.of("DEV-1", SensorHealthResponse.builder()
                .deviceUid("DEV-1")
                .overallStatus("HEALTHY")
                .sensors(Map.of(
                        "temperature", SensorHealthResponse.SensorStatusDetail.builder().status("HEALTHY").build(),
                        "rain", SensorHealthResponse.SensorStatusDetail.builder().status("HEALTHY").build()))
                .build()));
        when(ledger.window(anyCollection(), any())).thenReturn(Map.of("DEV-1", Map.of(
                "temperature", new SensorHealthLedger.Tally(50, 0, 0, NOW.minusHours(23)))));
        when(calibrationRepository.findLatestTwoForEach(anyCollection())).thenReturn(List.of());

        SensorHealthResponse report = service.forEach(List.of("DEV-1")).get("DEV-1");

        SensorHealthResponse.SensorStatusDetail temperature = report.getSensors().get("temperature");
        assertThat(temperature.getHealth().getPercent()).isEqualByComparingTo("100.0");
        assertThat(temperature.getCalibration().getStatus()).isEqualTo(CalibrationStatus.NOT_CALIBRATED);
        // Rain has nothing to calibrate, and no evaluations to score.
        assertThat(report.getSensors().get("rain").getCalibration()).isNull();
        assertThat(report.getSensors().get("rain").getHealth()).isNull();
    }

    private static SensorCalibration calibration(String offset, LocalDateTime at) {
        return SensorCalibration.builder()
                .sensorType("temperature")
                .offsetValue(new BigDecimal(offset))
                .calibratedAt(at)
                .validUntil(at.plusDays(180))
                .build();
    }
}
