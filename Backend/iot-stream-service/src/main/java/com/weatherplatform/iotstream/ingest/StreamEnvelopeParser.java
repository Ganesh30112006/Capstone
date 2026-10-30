package com.weatherplatform.iotstream.ingest;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Turns a raw MQTT message (topic + JSON body) or an internal-bus event into a
 * {@link StreamEnvelope}.
 *
 * <p>Parsing is deliberately lenient: a live-stream frame that cannot be fully
 * understood is still worth forwarding with whatever was extracted, because the
 * authoritative validation happens on the SQS branch. The only hard requirement
 * is a device identity — a frame with no {@code deviceUid} and no usable topic
 * is dropped.
 */
@Slf4j
@Component
public class StreamEnvelopeParser {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    private final ObjectMapper objectMapper;

    public StreamEnvelopeParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param topic  full MQTT topic, e.g. {@code weather/devices/esp32-01/telemetry}; may be null
     * @param body   raw JSON payload
     * @param source {@code "aws"} or {@code "rabbit"}
     * @return the envelope, or {@code null} if it cannot be attributed to a device
     */
    public StreamEnvelope parse(String topic, byte[] body, String source) {
        Map<String, Object> data;
        try {
            data = objectMapper.readValue(body, MAP_TYPE);
        } catch (Exception e) {
            log.debug("Unparseable stream payload on topic {}: {}", topic, e.toString());
            return null;
        }
        return parse(topic, data, source);
    }

    public StreamEnvelope parse(String topic, Map<String, Object> data, String source) {
        if (data == null) {
            return null;
        }
        String deviceUid = firstNonBlank(
                asString(data.get("deviceUid")),
                asString(data.get("device_uid")),
                topicSegment(topic, 2));
        if (deviceUid == null) {
            log.debug("Dropping stream frame with no device identity (topic={})", topic);
            return null;
        }

        StreamEnvelope.Type type = classify(topic, data);
        Instant timestamp = parseTimestamp(
                data.get("timestamp"), data.get("recordedAt"), data.get("brokerReceivedAt"));

        return StreamEnvelope.builder()
                .type(type)
                .deviceUid(deviceUid)
                .timestamp(timestamp)
                .receivedAt(Instant.now())
                .source(source)
                .data(data)
                .build();
    }

    private static StreamEnvelope.Type classify(String topic, Map<String, Object> data) {
        if (topic != null && topic.endsWith("/heartbeat")) {
            return StreamEnvelope.Type.heartbeat;
        }
        if (topic != null && topic.endsWith("/telemetry")) {
            return StreamEnvelope.Type.telemetry;
        }
        // No topic (bus source): infer from the payload shape.
        if (data.containsKey("batteryLevel") || data.containsKey("wifiSignalStrength")
                || data.containsKey("firmwareVersion")) {
            return StreamEnvelope.Type.heartbeat;
        }
        return StreamEnvelope.Type.telemetry;
    }

    static Instant parseTimestamp(Object... candidates) {
        for (Object raw : candidates) {
            if (raw == null) {
                continue;
            }
            if (raw instanceof Number n) {
                long v = n.longValue();
                // Heuristic: seconds vs milliseconds since the epoch.
                return v > 100_000_000_000L ? Instant.ofEpochMilli(v) : Instant.ofEpochSecond(v);
            }
            String s = raw.toString().trim();
            if (s.isEmpty()) {
                continue;
            }
            try {
                return OffsetDateTime.parse(s.endsWith("Z") || s.contains("+") ? s : s + "Z").toInstant();
            } catch (DateTimeParseException ignored) {
                try {
                    return Instant.parse(s);
                } catch (DateTimeParseException ignored2) {
                    // try next candidate
                }
            }
        }
        return null;
    }

    private static String topicSegment(String topic, int index) {
        if (topic == null) {
            return null;
        }
        String[] parts = topic.split("/");
        return index < parts.length ? emptyToNull(parts[index]) : null;
    }

    private static String asString(Object o) {
        return o == null ? null : emptyToNull(o.toString().trim());
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() ? null : s;
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
