package com.weatherplatform.alertservice.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

/**
 * Reads an ISO-8601 timestamp into a UTC {@link LocalDateTime}, with or without
 * an offset.
 *
 * <p>The platform stores every timestamp as UTC in a {@code LocalDateTime}, so
 * a bare {@code 2026-09-16T08:07:21.897832} is already what is wanted. But this
 * service also consumes {@code anomaly.detected} from the Python AI service,
 * and Python's {@code datetime.isoformat()} emits an offset:
 * {@code 2026-09-16T08:07:21.897832+00:00}. Jackson's stock deserializer
 * rejects that outright, so every anomaly the AI raised was retried three times
 * and dead-lettered — the alerting path for AI-detected anomalies was silently
 * dead while the rest of the platform looked healthy.
 *
 * <p>The offset is <em>converted</em>, not discarded. Dropping it would be
 * correct only for {@code +00:00}; a producer in any other zone would have its
 * readings silently misfiled by the size of its offset, which is exactly the
 * double-conversion class of bug this platform has already been bitten by once.
 */
public class UtcLocalDateTimeDeserializer extends JsonDeserializer<LocalDateTime> {

    @Override
    public LocalDateTime deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String raw = parser.getText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String text = raw.trim();
        try {
            // Offset-bearing: convert to the equivalent instant in UTC.
            return OffsetDateTime.parse(text).atZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException notAnOffset) {
            try {
                // Already local, already UTC by convention.
                return LocalDateTime.parse(text);
            } catch (DateTimeParseException notLocal) {
                try {
                    return LocalDateTime.ofInstant(Instant.parse(text), ZoneOffset.UTC);
                } catch (DateTimeParseException notAnInstant) {
                    throw new IOException("Unparseable timestamp: " + text, notAnInstant);
                }
            }
        }
    }
}
