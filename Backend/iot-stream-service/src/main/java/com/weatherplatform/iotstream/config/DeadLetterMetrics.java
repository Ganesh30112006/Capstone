package com.weatherplatform.iotstream.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;
import java.util.Properties;
import java.util.stream.Stream;

/**
 * Publishes the depth of this service's dead-letter queues as a gauge.
 *
 * <p>The platform routes every message it cannot process to a dead-letter
 * queue, and that is where the evidence of a failure waits to be looked at.
 * Nothing surfaced those depths anywhere: a queue could accumulate poison
 * messages indefinitely while every health check stayed green, every log line
 * scrolled away, and the only way to find out was to ask the broker's
 * management API by hand. A backlog nobody can see is not being handled, it is
 * only being stored.
 *
 * <p>The queues are discovered from the {@link Queue} beans this service
 * declares, so a dead-letter queue added later is measured without anyone
 * remembering to register it here.
 *
 * <p>An unreadable depth is reported as NaN rather than zero. Zero is a
 * measurement that says "nothing has been dead-lettered", and a broker this
 * service cannot reach has not told it that -- it has told it nothing. The two
 * must not render the same, which is the same rule the rest of this platform
 * follows for absent readings.
 */
@Configuration
public class DeadLetterMetrics {

    public static final String GAUGE_NAME = "messaging.deadletter.depth";

    @Bean
    public MeterBinder deadLetterDepthMetrics(ObjectProvider<Queue> declaredQueues,
                                              ObjectProvider<Declarables> declarableGroups,
                                              ObjectProvider<RabbitAdmin> rabbitAdmin) {
        /*
         * Both shapes count. A queue can be an individual @Bean, or one entry
         * in a Declarables group -- alert-service builds its four dead-letter
         * queues in a loop and returns them together, and looking only at Queue
         * beans found none of them, leaving the service with the most
         * dead-letter queues on the platform as the one with no gauges at all.
         */
        Stream<Queue> individual = declaredQueues.stream();
        Stream<Queue> grouped = declarableGroups.stream()
                .flatMap(group -> group.getDeclarables().stream())
                .filter(Queue.class::isInstance)
                .map(Queue.class::cast);

        List<String> deadLetterQueues = Stream.concat(individual, grouped)
                .map(Queue::getName)
                .filter(name -> name.endsWith(".dlq"))
                .distinct()
                .sorted()
                .toList();

        return registry -> deadLetterQueues.forEach(queue ->
                Gauge.builder(GAUGE_NAME, () -> depthOf(rabbitAdmin.getIfAvailable(), queue))
                        .tag("queue", queue)
                        .description("Messages waiting in a dead-letter queue; NaN when the depth could not be read")
                        .strongReference(true)
                        .register(registry));
    }

    /** Null becomes NaN in the gauge: unknown, not empty. */
    static Double depthOf(RabbitAdmin admin, String queue) {
        if (admin == null) {
            return null;
        }
        try {
            Properties properties = admin.getQueueProperties(queue);
            if (properties == null) {
                // The broker answered and has no such queue. That is still not
                // a depth of zero -- it is a topology that does not match what
                // this service believes it declared.
                return null;
            }
            Object count = properties.get(RabbitAdmin.QUEUE_MESSAGE_COUNT);
            return count instanceof Number number ? number.doubleValue() : null;
        } catch (RuntimeException brokerUnreachable) {
            return null;
        }
    }
}
