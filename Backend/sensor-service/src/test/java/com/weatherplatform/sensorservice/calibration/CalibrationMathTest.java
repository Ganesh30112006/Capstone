package com.weatherplatform.sensorservice.calibration;

import com.weatherplatform.sensorservice.entity.SensorCalibration;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CalibrationMathTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 9, 28, 12, 0);

    private static SensorCalibration calibration(String offset, LocalDateTime at, int validityDays) {
        return SensorCalibration.builder()
                .offsetValue(new BigDecimal(offset))
                .calibratedAt(at)
                .validUntil(at.plusDays(validityDays))
                .build();
    }

    @Nested
    class TheOffset {

        @Test
        void isTheReferenceMinusTheSensor() {
            assertThat(CalibrationMath.offset(new BigDecimal("25.30"), new BigDecimal("25.18")))
                    .isEqualByComparingTo("0.12");
            assertThat(CalibrationMath.offset(new BigDecimal("25.00"), new BigDecimal("25.20")))
                    .isEqualByComparingTo("-0.20");
        }

        @Test
        void isExactlyTheDifferenceOfTheStoredValues() {
            // The database checks offset = reference - sensor at three decimals.
            // Rounding the difference instead of the operands would break that:
            // 1.0005 - 0.0004 = 1.0001 -> 1.000, but 1.001 - 0.000 = 1.001.
            BigDecimal offset = CalibrationMath.offset(new BigDecimal("1.0005"), new BigDecimal("0.0004"));
            assertThat(offset).isEqualByComparingTo("1.001");
            assertThat(offset.scale()).isEqualTo(3);
        }
    }

    @Nested
    class Correction {

        @Test
        void addsTheOffsetToTheRawValue() {
            assertThat(CalibrationMath.correct("temperature", new BigDecimal("25.18"), new BigDecimal("0.120")))
                    .isEqualByComparingTo("25.30");
        }

        @Test
        void leavesAnUncalibratedSensorUntouched() {
            BigDecimal raw = new BigDecimal("25.18");
            assertThat(CalibrationMath.correct("temperature", raw, null)).isSameAs(raw);
            assertThat(CalibrationMath.correctIndex(80, null)).isEqualTo(80);
        }

        @Test
        void keepsAMissingValueMissing() {
            assertThat(CalibrationMath.correct("oxygen", null, new BigDecimal("0.5"))).isNull();
            assertThat(CalibrationMath.correctIndex(null, new BigDecimal("3"))).isNull();
        }

        @Test
        void stopsHumidityAtSaturation() {
            assertThat(CalibrationMath.correct("humidity", new BigDecimal("99.80"), new BigDecimal("0.500")))
                    .isEqualByComparingTo("100.00");
            assertThat(CalibrationMath.correct("humidity", new BigDecimal("0.20"), new BigDecimal("-0.500")))
                    .isEqualByComparingTo("0.00");
        }

        @Test
        void doesNotClampQuantitiesWithNoPercentCeiling() {
            assertThat(CalibrationMath.correct("pressure", new BigDecimal("1012.40"), new BigDecimal("1.250")))
                    .isEqualByComparingTo("1013.65");
        }

        @Test
        void roundsTheAirQualityIndexAndNeverGoesNegative() {
            assertThat(CalibrationMath.correctIndex(80, new BigDecimal("2.500"))).isEqualTo(83);
            assertThat(CalibrationMath.correctIndex(80, new BigDecimal("2.400"))).isEqualTo(82);
            assertThat(CalibrationMath.correctIndex(3, new BigDecimal("-10"))).isZero();
        }
    }

    @Nested
    class Status {

        @Test
        void aSensorNobodyHasCheckedIsNotCalibratedRatherThanValid() {
            assertThat(CalibrationMath.status(null, NOW, 14)).isEqualTo(CalibrationStatus.NOT_CALIBRATED);
        }

        @Test
        void isValidWellInsideItsPeriod() {
            assertThat(CalibrationMath.status(calibration("0", NOW.minusDays(10), 180), NOW, 14))
                    .isEqualTo(CalibrationStatus.VALID);
        }

        @Test
        void isDueSoonInsideTheWarningPeriod() {
            SensorCalibration c = calibration("0", NOW.minusDays(170), 180); // 10 days left
            assertThat(CalibrationMath.status(c, NOW, 14)).isEqualTo(CalibrationStatus.DUE_SOON);
        }

        @Test
        void becomesDueSoonExactlyAtTheWarningBoundary() {
            SensorCalibration c = calibration("0", NOW.minusDays(166), 180); // exactly 14 days left
            assertThat(CalibrationMath.status(c, NOW, 14)).isEqualTo(CalibrationStatus.DUE_SOON);
            assertThat(CalibrationMath.status(c, NOW.minusSeconds(1), 14)).isEqualTo(CalibrationStatus.VALID);
        }

        @Test
        void expiresTheMomentItsValidityEnds() {
            SensorCalibration c = calibration("0", NOW.minusDays(180), 180);
            assertThat(CalibrationMath.status(c, NOW, 14)).isEqualTo(CalibrationStatus.EXPIRED);
            assertThat(CalibrationMath.status(c, NOW.minusSeconds(1), 14)).isEqualTo(CalibrationStatus.DUE_SOON);
        }
    }

    @Nested
    class Drift {

        @Test
        void carriesTheRateBetweenTheLastTwoCalibrationsForward() {
            // Read true at the first calibration; 0.2 high ten days later, so the
            // bias grows 0.02 a day. Six days after that it is 0.12 further out.
            SensorCalibration previous = calibration("0.000", NOW.minusDays(16), 180);
            SensorCalibration latest = calibration("-0.200", NOW.minusDays(6), 180);

            assertThat(CalibrationMath.driftSinceLastCalibration(latest, previous, NOW))
                    .isEqualByComparingTo("0.120");
        }

        @Test
        void isNegativeForASensorDriftingLow() {
            SensorCalibration previous = calibration("0.000", NOW.minusDays(16), 180);
            SensorCalibration latest = calibration("0.200", NOW.minusDays(6), 180);

            assertThat(CalibrationMath.driftSinceLastCalibration(latest, previous, NOW))
                    .isEqualByComparingTo("-0.120");
        }

        @Test
        void isZeroOnTheDayOfTheLatestCalibration() {
            SensorCalibration previous = calibration("0.000", NOW.minusDays(10), 180);
            SensorCalibration latest = calibration("-0.200", NOW, 180);

            assertThat(CalibrationMath.driftSinceLastCalibration(latest, previous, NOW)).isEqualByComparingTo("0");
        }

        @Test
        void isUnknownRatherThanZeroWithOnlyOneCalibration() {
            SensorCalibration only = calibration("-0.200", NOW.minusDays(6), 180);

            assertThat(CalibrationMath.driftSinceLastCalibration(only, null, NOW)).isNull();
            assertThat(CalibrationMath.driftSinceLastCalibration(null, null, NOW)).isNull();
        }

        @Test
        void isNotExtrapolatedFromCalibrationsTakenTooCloseTogether() {
            // A minute apart and 0.1 different: as a rate that is 144 a day, and
            // a month later "drift" of thousands of degrees.
            SensorCalibration previous = calibration("0.000", NOW.minusDays(30).minusMinutes(1), 180);
            SensorCalibration latest = calibration("-0.100", NOW.minusDays(30), 180);

            assertThat(CalibrationMath.driftSinceLastCalibration(latest, previous, NOW)).isNull();
        }

        @Test
        void acceptsABaselineOfExactlyTheMinimum() {
            SensorCalibration previous = calibration("0.000", NOW.minusDays(14), 180);
            SensorCalibration latest = calibration("-0.070", NOW.minusDays(7), 180);

            assertThat(CalibrationMath.driftSinceLastCalibration(latest, previous, NOW))
                    .isEqualByComparingTo("0.070");
        }
    }
}
