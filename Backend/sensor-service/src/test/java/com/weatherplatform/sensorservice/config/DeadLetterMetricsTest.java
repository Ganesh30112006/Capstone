package com.weatherplatform.sensorservice.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A dead-letter depth that could not be read must not render as zero.
 *
 * <p>Zero is a measurement, and it says a specific thing: nothing has been
 * dead-lettered. A broker this service cannot reach has not said that -- it has
 * said nothing at all. Collapsing the two would draw a reassuring flat line
 * across exactly the period when nobody could see the queue, which is the same
 * failure as an empty list rendering as "no outages".
 */
class DeadLetterMetricsTest {

    private static final String DLQ = "sensor.telemetry.inbound.dlq";

    private static RabbitAdmin adminReporting(Object count) {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        Properties properties = new Properties();
        if (count != null) {
            properties.put(RabbitAdmin.QUEUE_MESSAGE_COUNT, count);
        }
        when(admin.getQueueProperties(anyString())).thenReturn(properties);
        return admin;
    }

    @Test
    @DisplayName("reports the broker's count when it can be read")
    void readsTheDepth() {
        assertThat(DeadLetterMetrics.depthOf(adminReporting(7), DLQ)).isEqualTo(7.0);
    }

    @Test
    @DisplayName("an empty queue is genuinely zero, and says so")
    void emptyIsZero() {
        assertThat(DeadLetterMetrics.depthOf(adminReporting(0), DLQ)).isZero();
    }

    @Test
    @DisplayName("an unreachable broker is unknown, not empty")
    void brokerDownIsUnknown() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueProperties(anyString()))
                .thenThrow(new AmqpConnectException(new RuntimeException("connection refused")));

        assertThat(DeadLetterMetrics.depthOf(admin, DLQ)).isNull();
    }

    @Test
    @DisplayName("a queue the broker does not have is unknown, not empty")
    void missingQueueIsUnknown() {
        RabbitAdmin admin = mock(RabbitAdmin.class);
        when(admin.getQueueProperties(anyString())).thenReturn(null);

        assertThat(DeadLetterMetrics.depthOf(admin, DLQ)).isNull();
    }

    @Test
    @DisplayName("a count the broker reports in an unexpected shape is unknown")
    void unreadableCountIsUnknown() {
        assertThat(DeadLetterMetrics.depthOf(adminReporting("lots"), DLQ)).isNull();
        assertThat(DeadLetterMetrics.depthOf(adminReporting(null), DLQ)).isNull();
    }

    @Test
    @DisplayName("no RabbitAdmin at all is unknown, not empty")
    void noAdminIsUnknown() {
        assertThat(DeadLetterMetrics.depthOf(null, DLQ)).isNull();
    }
}
