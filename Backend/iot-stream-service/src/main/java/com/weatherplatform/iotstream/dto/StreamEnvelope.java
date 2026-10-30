package com.weatherplatform.iotstream.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Map;

/**
 * The single frame shape used both on the internal bus ({@code iot.telemetry.stream},
 * {@code iot.heartbeat.stream}) and on the client WebSocket.
 *
 * <p>{@code data} is left as a map on purpose: this service does not own the
 * telemetry schema and must not silently drop fields a device or the AI service
 * adds later. Numeric coercion and validation are the AI service's and
 * sensor-service's job on their respective branches.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class StreamEnvelope implements Serializable {

    public enum Type {
        telemetry, heartbeat, prediction, report, anomaly, hello,
        // Device lifecycle, mirrored to the console from device-service.
        deviceRegistered, firmwareRequested, firmwareCompleted
    }

    private Type type;
    private String deviceUid;

    /** UTC. The device's own timestamp when present, else the broker receipt time. */
    private Instant timestamp;

    /** Broker/relay receipt time, always set by this service. */
    private Instant receivedAt;

    /** Which branch produced this frame: {@code aws} or {@code rabbit}. */
    private String source;

    /** The reading or AI payload. */
    private Map<String, Object> data;
}
