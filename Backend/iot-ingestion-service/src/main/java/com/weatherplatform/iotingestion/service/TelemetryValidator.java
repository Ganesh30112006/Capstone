package com.weatherplatform.iotingestion.service;

import com.weatherplatform.iotingestion.dto.IoTHeartbeatMessage;
import com.weatherplatform.iotingestion.dto.IoTTelemetryMessage;
import com.weatherplatform.iotingestion.exception.InvalidTelemetryException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/**
 * Structural gatekeeper for messages arriving from AWS IoT Core.
 *
 * Deliberately narrow: it checks only that a message is *processable* — it has
 * an identity, a plausible UTC timestamp, and coordinates that are on Earth.
 * Judging whether a reading is physically sane is sensor-service's
 * SensorFailureDetector, which has the device's history to compare against; a
 * 46 °C reading is a valid message and a possible sensor fault, and those are
 * different decisions made in different places.
 */
@Service
public class TelemetryValidator {

    /**
     * Tolerance for a device whose clock runs fast. Anything beyond this is a
     * clock fault or a forged timestamp, not a real future reading.
     */
    @Value("${iot.validation.max-clock-skew-minutes:15}")
    private long maxClockSkewMinutes;

    /**
     * Replay window. An old message is usually a device flushing a backlog
     * after an outage, which is legitimate; beyond this it is stale enough that
     * storing it would corrupt historical aggregates.
     */
    @Value("${iot.validation.max-message-age-days:7}")
    private long maxMessageAgeDays;

    public void validate(IoTTelemetryMessage msg) {
        requireIdentity(msg.getDeviceUid());
        requireSaneTimestamp(msg.getTimestamp(), msg.getDeviceUid());
        requireSaneCoordinates(msg.getLatitude(), msg.getLongitude());
    }

    public void validate(IoTHeartbeatMessage msg) {
        requireIdentity(msg.getDeviceUid());
        // A heartbeat with no timestamp is still useful — ingestion stamps it on
        // arrival — so only a present-but-implausible timestamp is rejected.
        if (msg.getTimestamp() != null) {
            requireSaneTimestamp(msg.getTimestamp(), msg.getDeviceUid());
        }
    }

    private void requireIdentity(String deviceUid) {
        if (deviceUid == null || deviceUid.isBlank()) {
            throw new InvalidTelemetryException("Missing deviceUid");
        }
        if (deviceUid.length() > 100) {
            throw new InvalidTelemetryException("deviceUid exceeds 100 characters");
        }
    }

    private void requireSaneTimestamp(Instant timestamp, String deviceUid) {
        if (timestamp == null) {
            throw new InvalidTelemetryException("Missing timestamp for device " + deviceUid);
        }

        Instant now = Instant.now();

        if (timestamp.isAfter(now.plus(Duration.ofMinutes(maxClockSkewMinutes)))) {
            throw new InvalidTelemetryException(
                    "Timestamp is more than " + maxClockSkewMinutes + " minutes in the future for device " + deviceUid);
        }
        if (timestamp.isBefore(now.minus(Duration.ofDays(maxMessageAgeDays)))) {
            throw new InvalidTelemetryException(
                    "Timestamp is older than " + maxMessageAgeDays + " days for device " + deviceUid);
        }
    }

    private void requireSaneCoordinates(Double latitude, Double longitude) {
        // GPS is optional; a station may report without a fix.
        if (latitude == null && longitude == null) {
            return;
        }
        if (latitude == null || longitude == null) {
            throw new InvalidTelemetryException("Latitude and longitude must be provided together");
        }
        if (latitude < -90 || latitude > 90) {
            throw new InvalidTelemetryException("Latitude " + latitude + " is outside [-90, 90]");
        }
        if (longitude < -180 || longitude > 180) {
            throw new InvalidTelemetryException("Longitude " + longitude + " is outside [-180, 180]");
        }
    }
}
