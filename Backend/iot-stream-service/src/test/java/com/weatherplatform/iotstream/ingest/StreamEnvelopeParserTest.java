package com.weatherplatform.iotstream.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class StreamEnvelopeParserTest {

    private final StreamEnvelopeParser parser = new StreamEnvelopeParser(new ObjectMapper());

    @Test
    void parsesTelemetryFromTopicAndBody() {
        String body = """
                {"deviceUid":"esp32-01","timestamp":"2026-09-01T10:15:30Z",
                 "temperature":21.4,"humidity":55.0,"pressure":1013.2}""";

        StreamEnvelope env = parser.parse("weather/devices/esp32-01/telemetry",
                body.getBytes(StandardCharsets.UTF_8), "aws");

        assertThat(env).isNotNull();
        assertThat(env.getType()).isEqualTo(StreamEnvelope.Type.telemetry);
        assertThat(env.getDeviceUid()).isEqualTo("esp32-01");
        assertThat(env.getTimestamp()).isEqualTo(Instant.parse("2026-09-01T10:15:30Z"));
        assertThat(env.getSource()).isEqualTo("aws");
        assertThat(env.getData()).containsEntry("temperature", 21.4);
    }

    @Test
    void derivesDeviceUidFromTopicWhenBodyOmitsIt() {
        StreamEnvelope env = parser.parse("weather/devices/station-9/telemetry",
                "{\"temperature\":20}".getBytes(StandardCharsets.UTF_8), "aws");

        assertThat(env).isNotNull();
        assertThat(env.getDeviceUid()).isEqualTo("station-9");
    }

    @Test
    void classifiesHeartbeatByTopicSuffix() {
        StreamEnvelope env = parser.parse("weather/devices/esp32-01/heartbeat",
                "{\"batteryLevel\":87,\"firmwareVersion\":\"1.4.2\"}".getBytes(StandardCharsets.UTF_8), "aws");

        assertThat(env).isNotNull();
        assertThat(env.getType()).isEqualTo(StreamEnvelope.Type.heartbeat);
    }

    @Test
    void classifiesHeartbeatByPayloadShapeWhenNoTopic() {
        StreamEnvelope env = parser.parse(null,
                Map.of("deviceUid", "esp32-01", "wifiSignalStrength", -61), "rabbit");

        assertThat(env).isNotNull();
        assertThat(env.getType()).isEqualTo(StreamEnvelope.Type.heartbeat);
    }

    @Test
    void returnsNullWhenNoDeviceIdentityIsAvailable() {
        StreamEnvelope env = parser.parse(null,
                "{\"temperature\":20}".getBytes(StandardCharsets.UTF_8), "aws");

        assertThat(env).isNull();
    }

    @Test
    void returnsNullOnUnparseableJson() {
        StreamEnvelope env = parser.parse("weather/devices/x/telemetry",
                "not json".getBytes(StandardCharsets.UTF_8), "aws");

        assertThat(env).isNull();
    }

    @Test
    void parseTimestampAcceptsEpochMillisAndSeconds() {
        assertThat(StreamEnvelopeParser.parseTimestamp(1_756_720_530_000L))
                .isEqualTo(Instant.ofEpochMilli(1_756_720_530_000L));
        assertThat(StreamEnvelopeParser.parseTimestamp(1_756_720_530L))
                .isEqualTo(Instant.ofEpochSecond(1_756_720_530L));
    }

    @Test
    void parseTimestampFallsThroughToNextCandidate() {
        Instant parsed = StreamEnvelopeParser.parseTimestamp("garbage", "2026-09-01T00:00:00Z");
        assertThat(parsed).isEqualTo(Instant.parse("2026-09-01T00:00:00Z"));
    }
}
