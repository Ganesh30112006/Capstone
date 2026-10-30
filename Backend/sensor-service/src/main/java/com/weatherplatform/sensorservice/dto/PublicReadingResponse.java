package com.weatherplatform.sensorservice.dto;

import lombok.Builder;
import lombok.Value;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Sanitised environmental reading served to unauthenticated public visitors.
 *
 * Carries only what a member of the public needs to read local conditions.
 * Deliberately omits the row ID and the {@code messageId} idempotency key,
 * which are internal plumbing and would leak ingestion volume and ordering.
 */
@Value
@Builder
public class PublicReadingResponse {

    String stationId;
    BigDecimal temperature;
    BigDecimal humidity;
    Integer airQuality;
    BigDecimal oxygen;
    BigDecimal pressure;
    Boolean rain;

    /** UTC. */
    LocalDateTime recordedAt;
}
