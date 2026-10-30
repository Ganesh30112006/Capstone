package com.weatherplatform.sensorservice.service;

import com.weatherplatform.sensorservice.config.SensorThresholdProperties;
import com.weatherplatform.sensorservice.dto.SensorDataRequest;
import com.weatherplatform.sensorservice.entity.SensorData;
import com.weatherplatform.sensorservice.repository.SensorDataRepository;
import com.weatherplatform.sensorservice.repository.SensorHealthRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SensorFailureDetectorTest {

    @Mock
    private SensorHealthRepository healthRepository;
    @Mock
    private SensorDataRepository dataRepository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock
    private SensorThresholdProperties props;
    @Mock
    private SensorHealthLedger ledger;

    @InjectMocks
    private SensorFailureDetector detector;

    @BeforeEach
    void setUp() {
        // Setup mock threshold ranges (min, max, rapidChange)
        SensorThresholdProperties.Range tempRange = new SensorThresholdProperties.Range(-40.0, 75.0, 25.0);
        SensorThresholdProperties.Range humRange = new SensorThresholdProperties.Range(0.0, 100.0, 45.0);
        SensorThresholdProperties.Range aqRange = new SensorThresholdProperties.Range(0.0, 500.0, null);
        SensorThresholdProperties.Range pressRange = new SensorThresholdProperties.Range(300.0, 1150.0, null);
        SensorThresholdProperties.Range oxRange = new SensorThresholdProperties.Range(5.0, 30.0, null);

        lenient().when(props.getTemperature()).thenReturn(tempRange);
        lenient().when(props.getHumidity()).thenReturn(humRange);
        lenient().when(props.getAirQuality()).thenReturn(aqRange);
        lenient().when(props.getPressure()).thenReturn(pressRange);
        lenient().when(props.getOxygen()).thenReturn(oxRange);
        lenient().when(props.getStuckReadingCount()).thenReturn(4);
        lenient().when(props.getStuckWindowSeconds()).thenReturn(240);
        lenient().when(props.getRecoveryHoldSeconds()).thenReturn(60);

        // No recent history — disables stuck / rate-of-change checks
        lenient().when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("DEV-1"), any(Pageable.class)))
                .thenReturn(Page.<SensorData>empty());
    }

    @Test
    void shouldReportHealthyWhenWithinRanges() {
        SensorDataRequest req = new SensorDataRequest();
        req.setDeviceUid("DEV-1");
        req.setTemperature(BigDecimal.valueOf(25.0));
        req.setHumidity(BigDecimal.valueOf(50.0));
        req.setAirQuality(45);
        req.setPressure(BigDecimal.valueOf(1013.25));
        req.setOxygen(BigDecimal.valueOf(21.0));

        Map<String, String> health = detector.evaluateAndRecordHealth(req);

        assertEquals("HEALTHY", health.get("temperature"));
        assertEquals("HEALTHY", health.get("humidity"));
        assertEquals("HEALTHY", health.get("airQuality"));
        assertEquals("HEALTHY", health.get("pressure"));
        assertEquals("HEALTHY", health.get("oxygen"));
        assertFalse(health.containsValue("FAILED"));
    }

    @Test
    void shouldReportFailedWhenOutOfBounds() {
        SensorDataRequest req = new SensorDataRequest();
        req.setDeviceUid("DEV-1");
        req.setTemperature(BigDecimal.valueOf(100.0)); // > 75.0 max limit
        req.setHumidity(BigDecimal.valueOf(50.0));
        req.setAirQuality(45);
        req.setPressure(BigDecimal.valueOf(1013.25));

        Map<String, String> health = detector.evaluateAndRecordHealth(req);

        assertEquals("FAILED", health.get("temperature"));
        assertTrue(health.containsValue("FAILED"));
    }

    /**
     * A stuck or out-of-range sensor is one condition, not one per reading. The
     * detector used to publish on every reading for as long as the condition
     * lasted, which at a one-second reporting interval is four events a second
     * from a single flatlined station, each restating what the first one said.
     */
    @Test
    void announcesAFailureOnceRatherThanOnEveryReading() {
        for (int i = 0; i < 5; i++) {
            detector.evaluateAndRecordHealth(failingRequest());
        }

        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void announcesAgainWhenTheSensorRecoversAndFailsOnceMore() {
        // With no hold configured, a clean reading ends the condition at once.
        when(props.getRecoveryHoldSeconds()).thenReturn(0);
        detector.evaluateAndRecordHealth(failingRequest());
        detector.evaluateAndRecordHealth(failingRequest());

        SensorDataRequest healthy = failingRequest();
        healthy.setTemperature(BigDecimal.valueOf(25.0));
        detector.evaluateAndRecordHealth(healthy);

        // ...so the next failure is a new one and is announced immediately.
        detector.evaluateAndRecordHealth(failingRequest());

        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    /**
     * A value sitting on a threshold alternates — bad, fine, bad, fine — and
     * treating each clean reading as a recovery made every other reading a
     * brand-new failure to announce.
     */
    @Test
    void doesNotTreatOneCleanReadingAsARecovery() {
        SensorDataRequest healthy = failingRequest();
        healthy.setTemperature(BigDecimal.valueOf(25.0));

        for (int i = 0; i < 5; i++) {
            detector.evaluateAndRecordHealth(failingRequest());
            detector.evaluateAndRecordHealth(healthy);
        }

        // One condition, flapping — not five failures.
        verify(rabbitTemplate, times(1)).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void announcesAChangeOfStatusOnTheSameSensor() {
        detector.evaluateAndRecordHealth(failingRequest());

        // Absent value reads as FAILED too, so move the other way: a reading
        // that is present but out of range on a different sensor is its own
        // condition and must not be suppressed by the temperature one.
        SensorDataRequest alsoPressure = failingRequest();
        alsoPressure.setPressure(BigDecimal.valueOf(2000.0));
        detector.evaluateAndRecordHealth(alsoPressure);

        verify(rabbitTemplate, times(2)).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    /** Suppression is not silence: a standing condition is re-announced. */
    @Test
    void reAssertsAStandingFailureOnAKnownInterval() {
        assertEquals(java.time.Duration.ofMinutes(5), SensorFailureDetector.REASSERT_AFTER);
    }

    /**
     * The bug this pins: "stuck" was four identical readings, which is four
     * minutes of a frozen value from a station reporting once a minute and four
     * seconds from one reporting once a second. A real sensor reporting two
     * decimal places repeats itself for a few seconds routinely.
     */
    @Test
    void doesNotCallASensorStuckJustBecauseReadingsArrivedQuickly() {
        SensorDataRequest steady = new SensorDataRequest();
        steady.setDeviceUid("DEV-1");
        steady.setTemperature(BigDecimal.valueOf(25.0));
        steady.setHumidity(BigDecimal.valueOf(50.0));
        steady.setAirQuality(45);
        steady.setPressure(BigDecimal.valueOf(1013.25));
        steady.setOxygen(BigDecimal.valueOf(21.0));

        Map<String, String> health = null;
        for (int i = 0; i < 8; i++) {
            health = detector.evaluateAndRecordHealth(steady);
        }

        assertFalse(health.containsValue("WARNING"), "eight readings inside a second is not a stuck sensor");
        verify(rabbitTemplate, never()).convertAndSend(anyString(), anyString(), any(Object.class));
    }

    @Test
    void callsASensorStuckOnceTheValueHasBeenHeldForTheWindow() {
        // The window is what decides; with it at zero the sample count alone
        // does, which is the behaviour a deployment can opt back into.
        when(props.getStuckWindowSeconds()).thenReturn(0);

        SensorDataRequest steady = new SensorDataRequest();
        steady.setDeviceUid("DEV-1");
        steady.setTemperature(BigDecimal.valueOf(25.0));
        steady.setHumidity(BigDecimal.valueOf(50.0));
        steady.setAirQuality(45);
        steady.setPressure(BigDecimal.valueOf(1013.25));
        steady.setOxygen(BigDecimal.valueOf(21.0));

        Map<String, String> health = null;
        for (int i = 0; i < 4; i++) {
            health = detector.evaluateAndRecordHealth(steady);
        }

        assertEquals("WARNING", health.get("temperature"));
    }

    /**
     * The matching bug on the other check: `|now - previous| > limit` is a rate
     * only if every station reports on the same interval. Written that way, the
     * check lost sensitivity as a station reported more often and raised faults
     * on stations that reported less often.
     */
    @Test
    void measuresRateOfChangePerMinuteRatherThanPerReading() {
        SensorData aMinuteAgo = new SensorData();
        aMinuteAgo.setTemperature(BigDecimal.valueOf(20.0));
        aMinuteAgo.setRecordedAt(LocalDateTime.now().minusMinutes(1));
        when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("DEV-2"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(aMinuteAgo)));

        SensorDataRequest jump = new SensorDataRequest();
        jump.setDeviceUid("DEV-2");
        jump.setTemperature(BigDecimal.valueOf(50.0)); // +30 in one minute, over the 25/min limit
        jump.setHumidity(BigDecimal.valueOf(50.0));
        jump.setAirQuality(45);
        jump.setPressure(BigDecimal.valueOf(1013.25));

        assertEquals("WARNING", detector.evaluateAndRecordHealth(jump).get("temperature"));
    }

    @Test
    void doesNotFaultAStationThatSimplyReportsRarely() {
        // The same 30°C difference, spread across four hours of a real day.
        SensorData hoursAgo = new SensorData();
        hoursAgo.setTemperature(BigDecimal.valueOf(20.0));
        hoursAgo.setRecordedAt(LocalDateTime.now().minusHours(4));
        when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("DEV-3"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(hoursAgo)));

        SensorDataRequest warmAfternoon = new SensorDataRequest();
        warmAfternoon.setDeviceUid("DEV-3");
        warmAfternoon.setTemperature(BigDecimal.valueOf(50.0));
        warmAfternoon.setHumidity(BigDecimal.valueOf(50.0));
        warmAfternoon.setAirQuality(45);
        warmAfternoon.setPressure(BigDecimal.valueOf(1013.25));

        assertEquals("HEALTHY", detector.evaluateAndRecordHealth(warmAfternoon).get("temperature"));
    }

    @Test
    void doesNotFaultOrdinaryNoiseBetweenReadingsASecondApart() {
        SensorData aSecondAgo = new SensorData();
        aSecondAgo.setTemperature(BigDecimal.valueOf(30.0));
        aSecondAgo.setRecordedAt(LocalDateTime.now().minusSeconds(1));
        when(dataRepository.findByDeviceUidOrderByRecordedAtDesc(eq("DEV-4"), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(aSecondAgo)));

        SensorDataRequest next = new SensorDataRequest();
        next.setDeviceUid("DEV-4");
        next.setTemperature(BigDecimal.valueOf(30.1)); // 6 °C/min, well inside the limit
        next.setHumidity(BigDecimal.valueOf(50.0));
        next.setAirQuality(45);
        next.setPressure(BigDecimal.valueOf(1013.25));

        assertEquals("HEALTHY", detector.evaluateAndRecordHealth(next).get("temperature"));
    }

    private SensorDataRequest failingRequest() {
        SensorDataRequest req = new SensorDataRequest();
        req.setDeviceUid("DEV-1");
        req.setTemperature(BigDecimal.valueOf(100.0)); // above the 75.0 maximum
        req.setHumidity(BigDecimal.valueOf(50.0));
        req.setAirQuality(45);
        req.setPressure(BigDecimal.valueOf(1013.25));
        req.setOxygen(BigDecimal.valueOf(21.0));
        return req;
    }
}
