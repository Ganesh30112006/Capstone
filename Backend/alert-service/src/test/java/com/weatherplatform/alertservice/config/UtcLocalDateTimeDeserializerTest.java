package com.weatherplatform.alertservice.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This service consumes {@code anomaly.detected} from the Python AI service.
 *
 * Python's {@code datetime.isoformat()} writes an offset --
 * {@code 2026-09-16T08:07:21.897832+00:00} -- and Jackson's stock
 * LocalDateTime reader rejects that outright. Every anomaly the AI raised was
 * retried three times and dead-lettered, so AI-detected anomalies never became
 * alerts while every dashboard still looked healthy. That is the failure this
 * class exists to prevent, and the reason it converts the offset rather than
 * trimming it.
 */
class UtcLocalDateTimeDeserializerTest {

    private record Event(LocalDateTime detectedAt) {}

    private final ObjectMapper mapper = buildMapper();

    private static ObjectMapper buildMapper() {
        JavaTimeModule module = new JavaTimeModule();
        module.addDeserializer(LocalDateTime.class, new UtcLocalDateTimeDeserializer());
        return new ObjectMapper()
                .registerModule(module)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    private LocalDateTime read(String value) throws Exception {
        return mapper.readValue("{\"detectedAt\":" + value + "}", Event.class).detectedAt();
    }

    @Test
    @DisplayName("accepts the offset form the Python service emits")
    void acceptsAZeroOffset() throws Exception {
        assertThat(read("\"2026-09-16T08:07:21.897832+00:00\""))
                .isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 7, 21, 897_832_000));
    }

    @Test
    @DisplayName("converts a non-zero offset to UTC instead of discarding it")
    void convertsANonZeroOffset() throws Exception {
        // 13:37+05:30 is 08:07 UTC. Trimming the offset would file this reading
        // five and a half hours late and nothing downstream could tell.
        assertThat(read("\"2026-09-16T13:37:21+05:30\""))
                .isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 7, 21));
    }

    @Test
    @DisplayName("accepts a bare local timestamp, which is already UTC by convention")
    void acceptsANaiveTimestamp() throws Exception {
        assertThat(read("\"2026-09-16T08:07:21.897832\""))
                .isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 7, 21, 897_832_000));
    }

    @Test
    @DisplayName("accepts a Z-suffixed instant")
    void acceptsZuluTime() throws Exception {
        assertThat(read("\"2026-09-16T08:07:21Z\""))
                .isEqualTo(LocalDateTime.of(2026, 9, 16, 8, 7, 21));
    }

    @Test
    @DisplayName("a null or empty timestamp reads as unknown, not as now")
    void emptyIsNullNotNow() throws Exception {
        assertThat(read("null")).isNull();
        assertThat(read("\"\"")).isNull();
        assertThat(read("\"   \"")).isNull();
    }

    @Test
    @DisplayName("an unparseable timestamp fails loudly rather than being invented")
    void garbageIsRejected() {
        // Substituting the current time here would give an anomaly a detection
        // time it never had, and the alert would look freshly raised.
        assertThatThrownBy(() -> read("\"yesterday afternoon\""))
                .hasMessageContaining("Unparseable timestamp");
    }
}
