package com.weatherplatform.sensorservice.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The service's clock, as a bean so time-dependent rules -- calibration expiry,
 * drift, how recent a reading must be -- can be tested at a fixed instant.
 * UTC, matching every timestamp the platform stores.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
