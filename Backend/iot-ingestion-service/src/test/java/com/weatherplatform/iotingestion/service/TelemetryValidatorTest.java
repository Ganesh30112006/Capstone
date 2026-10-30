package com.weatherplatform.iotingestion.service;

import com.weatherplatform.iotingestion.dto.IoTHeartbeatMessage;
import com.weatherplatform.iotingestion.dto.IoTTelemetryMessage;
import com.weatherplatform.iotingestion.exception.InvalidTelemetryException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TelemetryValidatorTest {

    private TelemetryValidator validator;

    @BeforeEach
    void setUp() {
        validator = new TelemetryValidator();
        // @Value fields are not injected when constructing directly.
        ReflectionTestUtils.setField(validator, "maxClockSkewMinutes", 15L);
        ReflectionTestUtils.setField(validator, "maxMessageAgeDays", 7L);
    }

    private IoTTelemetryMessage validMessage() {
        IoTTelemetryMessage msg = new IoTTelemetryMessage();
        msg.setDeviceUid("STATION-001");
        msg.setTimestamp(Instant.now());
        msg.setTemperature(BigDecimal.valueOf(25.0));
        return msg;
    }

    @Test
    void acceptsWellFormedMessage() {
        assertDoesNotThrow(() -> validator.validate(validMessage()));
    }

    @Test
    void rejectsMissingDeviceUid() {
        IoTTelemetryMessage msg = validMessage();
        msg.setDeviceUid(null);

        InvalidTelemetryException ex = assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
        assertTrue(ex.getMessage().contains("Missing deviceUid"));
    }

    @Test
    void rejectsBlankDeviceUid() {
        IoTTelemetryMessage msg = validMessage();
        msg.setDeviceUid("   ");

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    void rejectsOverlongDeviceUid() {
        IoTTelemetryMessage msg = validMessage();
        msg.setDeviceUid("X".repeat(101));

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    void rejectsMissingTimestamp() {
        IoTTelemetryMessage msg = validMessage();
        msg.setTimestamp(null);

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    @DisplayName("A timestamp beyond the clock-skew tolerance is rejected")
    void rejectsFarFutureTimestamp() {
        IoTTelemetryMessage msg = validMessage();
        msg.setTimestamp(Instant.now().plus(2, ChronoUnit.HOURS));

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    @DisplayName("Minor clock skew is tolerated — devices drift")
    void acceptsSmallClockSkew() {
        IoTTelemetryMessage msg = validMessage();
        msg.setTimestamp(Instant.now().plus(5, ChronoUnit.MINUTES));

        assertDoesNotThrow(() -> validator.validate(msg));
    }

    @Test
    @DisplayName("A stale replay outside the retention window is rejected")
    void rejectsStaleTimestamp() {
        IoTTelemetryMessage msg = validMessage();
        msg.setTimestamp(Instant.now().minus(30, ChronoUnit.DAYS));

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    @DisplayName("A device flushing a short backlog after an outage is accepted")
    void acceptsRecentBacklog() {
        IoTTelemetryMessage msg = validMessage();
        msg.setTimestamp(Instant.now().minus(2, ChronoUnit.DAYS));

        assertDoesNotThrow(() -> validator.validate(msg));
    }

    @Test
    void acceptsMessageWithoutGpsFix() {
        IoTTelemetryMessage msg = validMessage();
        msg.setLatitude(null);
        msg.setLongitude(null);

        assertDoesNotThrow(() -> validator.validate(msg));
    }

    @Test
    void rejectsHalfProvidedCoordinates() {
        IoTTelemetryMessage msg = validMessage();
        msg.setLatitude(15.5057);
        msg.setLongitude(null);

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    void rejectsOutOfRangeLatitude() {
        IoTTelemetryMessage msg = validMessage();
        msg.setLatitude(120.0);
        msg.setLongitude(80.0499);

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    void rejectsOutOfRangeLongitude() {
        IoTTelemetryMessage msg = validMessage();
        msg.setLatitude(15.5057);
        msg.setLongitude(200.0);

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }

    @Test
    void acceptsValidCoordinates() {
        IoTTelemetryMessage msg = validMessage();
        msg.setLatitude(15.5057);
        msg.setLongitude(80.0499);

        assertDoesNotThrow(() -> validator.validate(msg));
    }

    @Test
    @DisplayName("An out-of-envelope reading is still a valid message — sensor-service judges it")
    void doesNotRejectExtremeReadings() {
        IoTTelemetryMessage msg = validMessage();
        msg.setTemperature(BigDecimal.valueOf(150.0));

        assertDoesNotThrow(() -> validator.validate(msg));
    }

    @Test
    void heartbeatWithoutTimestampIsAccepted() {
        IoTHeartbeatMessage msg = new IoTHeartbeatMessage();
        msg.setDeviceUid("STATION-001");
        msg.setTimestamp(null);

        // Ingestion stamps arrival time; a heartbeat still proves liveness.
        assertDoesNotThrow(() -> validator.validate(msg));
    }

    @Test
    void heartbeatWithoutDeviceUidIsRejected() {
        IoTHeartbeatMessage msg = new IoTHeartbeatMessage();
        msg.setDeviceUid(null);

        assertThrows(InvalidTelemetryException.class, () -> validator.validate(msg));
    }
}
