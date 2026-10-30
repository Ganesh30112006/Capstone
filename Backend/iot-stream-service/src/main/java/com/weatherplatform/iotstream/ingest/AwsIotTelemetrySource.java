package com.weatherplatform.iotstream.ingest;

import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.service.TelemetryFanoutService;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;

import java.net.URI;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Production telemetry source: a direct subscription to AWS IoT Core over
 * MQTT-over-WebSocket, authenticated with a SigV4 pre-signed URL from the
 * task's IAM role.
 *
 * <p>This is a genuine second consumer of the broker, parallel to the IoT Rule
 * that feeds SQS. No device certificate is used — this is a backend identity,
 * not a device — and it only ever subscribes; it never publishes toward a
 * device.
 *
 * <p>A SigV4 pre-signed URL is only valid for the handshake, so Paho's own
 * auto-reconnect (which would replay the stale URL) is disabled. Reconnection
 * is handled here: on connection loss a fresh URL is minted and a new client
 * built, with capped exponential backoff. A failure to connect is logged and
 * leaves the service running degraded — the SQS branch is unaffected, and the
 * streaming branch is never a hard dependency.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "iot.stream.source", havingValue = "aws")
public class AwsIotTelemetrySource implements TelemetrySource, SmartLifecycle {

    private final IotStreamProperties properties;
    private final StreamEnvelopeParser parser;
    private final TelemetryFanoutService fanout;
    private final AwsIotWebSocketUrlSigner urlSigner;

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong backoffSeconds = new AtomicLong(1);

    private ScheduledExecutorService reconnector;
    private volatile MqttClient client;

    public AwsIotTelemetrySource(IotStreamProperties properties,
                                 StreamEnvelopeParser parser,
                                 TelemetryFanoutService fanout) {
        this.properties = properties;
        this.parser = parser;
        this.fanout = fanout;
        this.urlSigner = new AwsIotWebSocketUrlSigner(
                DefaultCredentialsProvider.create(), properties.getAws().getRegion());
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        IotStreamProperties.Aws aws = properties.getAws();
        if (aws.getEndpoint() == null || aws.getEndpoint().isBlank()) {
            log.error("iot.stream.source=aws but iot.stream.aws.endpoint is not set; the live stream will be inactive");
            return;
        }
        backoffSeconds.set(Math.max(1, aws.getReconnectMinSeconds()));
        reconnector = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "aws-iot-reconnect");
            t.setDaemon(true);
            return t;
        });
        reconnector.execute(this::connectOnce);
    }

    private void connectOnce() {
        if (!running.get()) {
            return;
        }
        IotStreamProperties.Aws aws = properties.getAws();
        try {
            disposeClient();

            URI url = urlSigner.presignedUrl(aws.getEndpoint());
            MqttClient mqtt = new MqttClient(url.toString(), aws.getClientId(), new MemoryPersistence());

            MqttConnectOptions options = new MqttConnectOptions();
            options.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
            options.setCleanSession(true);
            options.setAutomaticReconnect(false); // stale signed URL cannot be replayed
            options.setConnectionTimeout(10);
            options.setKeepAliveInterval(30);

            mqtt.setCallback(new Callback());
            mqtt.connect(options);
            this.client = mqtt;

            for (String filter : aws.getTopicFilters()) {
                mqtt.subscribe(filter, 1);
                log.info("Subscribed to {}", filter);
            }
            connected.set(true);
            backoffSeconds.set(Math.max(1, aws.getReconnectMinSeconds()));
            log.info("Connected to AWS IoT Core at {} as {}", aws.getEndpoint(), aws.getClientId());

        } catch (Exception e) {
            connected.set(false);
            long delay = backoffSeconds.get();
            log.warn("AWS IoT connection attempt failed ({}); retrying in {}s", e.toString(), delay);
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!running.get() || reconnector == null) {
            return;
        }
        long delay = backoffSeconds.get();
        long max = Math.max(delay, properties.getAws().getReconnectMaxSeconds());
        backoffSeconds.set(Math.min(delay * 2, max));
        reconnector.schedule(this::connectOnce, delay, TimeUnit.SECONDS);
    }

    private void disposeClient() {
        MqttClient current = this.client;
        this.client = null;
        if (current == null) {
            return;
        }
        try {
            if (current.isConnected()) {
                current.disconnectForcibly(1000);
            }
            current.close();
        } catch (MqttException ignored) {
            // tearing down anyway
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        connected.set(false);
        if (reconnector != null) {
            reconnector.shutdownNow();
            reconnector = null;
        }
        disposeClient();
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public String name() {
        return "aws";
    }

    /** Start after messaging and the web layer are up. */
    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 100;
    }

    private final class Callback implements MqttCallbackExtended {

        @Override
        public void connectComplete(boolean reconnect, String serverURI) {
            connected.set(true);
        }

        @Override
        public void connectionLost(Throwable cause) {
            connected.set(false);
            log.warn("AWS IoT connection lost: {}", cause == null ? "unknown" : cause.toString());
            scheduleReconnect();
        }

        @Override
        public void messageArrived(String topic, MqttMessage message) {
            try {
                StreamEnvelope envelope = parser.parse(topic, message.getPayload(), "aws");
                if (envelope != null) {
                    fanout.accept(envelope);
                }
            } catch (RuntimeException e) {
                // A bad frame must never kill the Paho callback thread.
                log.debug("Dropping unprocessable IoT message on {}: {}", topic, e.toString());
            }
        }

        @Override
        public void deliveryComplete(IMqttDeliveryToken token) {
            // This client only subscribes.
        }
    }
}
