package com.weatherplatform.sensorservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Calibration policy: how long a calibration stays valid, and how large a
 * correction is believable, per sensor type.
 *
 * These are this deployment's maintenance policy, not properties of the
 * hardware, which is why they are configuration. The defaults are ordinary
 * field-station practice: gas sensors drift fastest and are re-checked most
 * often; barometers are stable and go longest.
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "sensor.calibration")
public class CalibrationProperties {

    /**
     * Per sensor type. Only these types can be calibrated: rain is a yes/no with
     * nothing to offset, and GPS is a position rather than a measurement.
     */
    private Map<String, Policy> sensors = defaults();

    /** A calibration is reported DUE_SOON this many days before it expires. */
    private int dueSoonDays = 14;

    /**
     * How recent the sensor's latest reading must be to calibrate against it
     * when the operator gives only the reference value. Calibration pairs two
     * readings taken together; a reading from an hour ago is not "together".
     */
    private int maxReadingAgeSeconds = 300;

    @Data
    public static class Policy {
        private int validityDays;
        /**
         * The largest correction accepted, either direction. A calibration
         * implying a bigger error than this is more likely a typo or a failed
         * sensor than a sensor that merely needs correcting.
         */
        private double maxOffset;

        public Policy() {}

        public Policy(int validityDays, double maxOffset) {
            this.validityDays = validityDays;
            this.maxOffset = maxOffset;
        }
    }

    private static Map<String, Policy> defaults() {
        Map<String, Policy> policies = new LinkedHashMap<>();
        policies.put("temperature", new Policy(180, 5.0));    // °C
        policies.put("humidity", new Policy(180, 15.0));      // %RH
        policies.put("airQuality", new Policy(90, 100.0));    // index points
        policies.put("pressure", new Policy(365, 20.0));      // hPa
        policies.put("oxygen", new Policy(180, 3.0));         // % O2
        return policies;
    }

    public boolean isCalibratable(String sensorType) {
        return sensorType != null && sensors.containsKey(sensorType);
    }

    public Policy policyFor(String sensorType) {
        return sensors.get(sensorType);
    }
}
