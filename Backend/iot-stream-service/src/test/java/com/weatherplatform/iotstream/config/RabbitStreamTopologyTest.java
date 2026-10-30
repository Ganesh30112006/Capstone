package com.weatherplatform.iotstream.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Queue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the half of a dead-letter setup that is easy to leave out.
 *
 * <p>Declaring {@code x-dead-letter-routing-key} on a queue is only one third of
 * the arrangement: the dead-letter queue has to exist, and it has to be bound to
 * the dead-letter exchange under that same key. Both source queues here named a
 * key and neither had the other two parts, so the broker routed every rejected
 * message to a key nothing was bound to and discarded it. The listener logged
 * the rejection, which made the handling look complete, while the payload was
 * gone and there was nothing left to inspect or replay.
 *
 * <p>The check below reads whatever the configuration declares rather than a
 * fixed list of names, so a queue added later with a dead-letter key and no
 * dead-letter queue fails here too.
 */
class RabbitStreamTopologyTest {

    private final RabbitStreamConfig config = new RabbitStreamConfig();

    private <T> List<T> beansOfType(Class<T> type) {
        List<T> found = new ArrayList<>();
        for (Method m : RabbitStreamConfig.class.getDeclaredMethods()) {
            if (type.isAssignableFrom(m.getReturnType()) && m.getParameterCount() == 0) {
                try {
                    found.add(type.cast(m.invoke(config)));
                } catch (ReflectiveOperationException e) {
                    throw new AssertionError("Could not read bean " + m.getName(), e);
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("every queue that names a dead-letter key has a queue bound to receive it")
    void noQueueDeadLettersIntoNothing() {
        List<Queue> queues = beansOfType(Queue.class);

        Set<String> declaredQueues = new HashSet<>();
        queues.forEach(q -> declaredQueues.add(q.getName()));

        Set<String> boundOnTheDlx = new HashSet<>();
        for (Binding b : beansOfType(Binding.class)) {
            if (RabbitStreamConfig.DLX.equals(b.getExchange())) {
                boundOnTheDlx.add(b.getRoutingKey());
            }
        }

        for (Queue q : queues) {
            Object key = q.getArguments().get("x-dead-letter-routing-key");
            if (key == null) {
                continue;
            }
            assertThat(declaredQueues)
                    .as("queue '%s' dead-letters to '%s', but no such queue is declared - "
                            + "rejected messages would be discarded by the broker", q.getName(), key)
                    .contains(key.toString());
            assertThat(boundOnTheDlx)
                    .as("queue '%s' dead-letters to '%s', but nothing is bound to that key on '%s' - "
                            + "rejected messages would be discarded by the broker",
                            q.getName(), key, RabbitStreamConfig.DLX)
                    .contains(key.toString());
        }
    }

    @Test
    @DisplayName("both source dead-letter queues are durable")
    void sourceQueuesHaveDurableDeadLetterQueues() {
        assertThat(config.streamSourceTelemetryDlq().getName())
                .isEqualTo(RabbitStreamConfig.QUEUE_SOURCE_TELEMETRY + ".dlq");
        assertThat(config.streamSourceHeartbeatDlq().getName())
                .isEqualTo(RabbitStreamConfig.QUEUE_SOURCE_HEARTBEAT + ".dlq");

        // A dead-letter queue that did not survive a broker restart would lose
        // the evidence it exists to keep.
        assertThat(config.streamSourceTelemetryDlq().isDurable()).isTrue();
        assertThat(config.streamSourceHeartbeatDlq().isDurable()).isTrue();
    }

    @Test
    @DisplayName("the mirror queue is intentionally transient and has no dead-letter queue")
    void theMirrorQueueIsDeliberatelyDifferent() {
        Queue mirror = config.aiMirrorQueue();

        // This one is meant to be disposable: it mirrors AI events to browsers,
        // those events still reach their durable consumers elsewhere, and a
        // queue growing while nobody is watching would be the worse outcome.
        assertThat(mirror.isDurable()).isFalse();
        assertThat(mirror.isAutoDelete()).isTrue();
        assertThat(mirror.getArguments()).doesNotContainKey("x-dead-letter-routing-key");
    }
}
