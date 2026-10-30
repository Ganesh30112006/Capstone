package com.weatherplatform.sensorservice.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "sensor.thresholds")
public class SensorThresholdProperties {

    private Range temperature = new Range(-40.0, 75.0, 25.0);
    private Range humidity = new Range(0.0, 100.0, 45.0);
    private Range airQuality = new Range(0.0, 500.0, null);
    private Range pressure = new Range(300.0, 1150.0, null);
    private Range oxygen = new Range(5.0, 30.0, null);
    private int stuckReadingCount = 4;

    /**
     * How long a value must stay identical before the sensor producing it is
     * called stuck.
     *
     * The count above cannot carry this on its own. Four identical readings
     * means four minutes of a frozen value from a station reporting once a
     * minute, and four seconds from one reporting once a second — the same
     * configuration describing two different faults, depending on a reporting
     * interval that is never stated anywhere. Both conditions now have to hold:
     * enough samples, and enough time.
     */
    private int stuckWindowSeconds = 240;

    /**
     * How long a sensor must read healthy before the failure it was reporting
     * is treated as over.
     *
     * Without it, one clean reading ends the condition and the next bad one is
     * a brand-new failure to announce. A value sitting on a threshold alternates
     * — bad, fine, bad, fine — and every other reading becomes an event. The
     * hold does not delay anything being reported: a failure is still announced
     * the moment it appears. It only decides when a condition counts as ended.
     */
    private int recoveryHoldSeconds = 60;

    @Data
    public static class Range {
        private Double min;
        private Double max;
        private Double rapidChange;

        public Range() {}

        public Range(Double min, Double max, Double rapidChange) {
            this.min = min;
            this.max = max;
            this.rapidChange = rapidChange;
        }
    }
}
