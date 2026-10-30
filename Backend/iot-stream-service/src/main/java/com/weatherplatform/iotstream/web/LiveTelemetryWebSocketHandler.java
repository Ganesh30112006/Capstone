package com.weatherplatform.iotstream.web;

import com.weatherplatform.iotstream.dto.StreamEnvelope;
import com.weatherplatform.iotstream.ingest.TelemetrySource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Server endpoint for authorized admin browser clients.
 *
 * <p>The socket is strictly one-directional: the server streams live readings
 * and AI output to the client. Any inbound frame other than a {@code ping}
 * keep-alive is ignored — this endpoint deliberately exposes no way to send a
 * command toward a device. Control of the fleet goes through the REST API
 * ({@code Admin UI -> API Gateway -> Device Service -> AWS IoT Core}), never a
 * socket, and never the browser directly.
 */
@Slf4j
@Component
public class LiveTelemetryWebSocketHandler extends TextWebSocketHandler {

    private final LiveSessionRegistry registry;
    private final TelemetrySource telemetrySource;

    public LiveTelemetryWebSocketHandler(LiveSessionRegistry registry,
                                         TelemetrySource telemetrySource) {
        this.registry = registry;
        this.telemetrySource = telemetrySource;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        if (!registry.register(session)) {
            session.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        Object principal = session.getAttributes().get("userEmail");
        StreamEnvelope hello = StreamEnvelope.builder()
                .type(StreamEnvelope.Type.hello)
                .receivedAt(Instant.now())
                .source(telemetrySource.name())
                .data(Map.of(
                        "role", String.valueOf(session.getAttributes().getOrDefault("userRole", "")),
                        "user", principal == null ? "" : principal,
                        "streamConnected", telemetrySource.isConnected()))
                .build();
        // Through the registry, not directly: the session is already registered
        // and therefore already reachable by the broadcast thread.
        registry.sendTo(session, hello);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // Keep-alive only. Everything else is intentionally inert.
        String payload = message.getPayload();
        if (payload != null && payload.contains("ping")) {
            // Same lock as the broadcast thread: a pong written directly would
            // race fan-out on this session exactly as the opening frame did.
            registry.sendTo(session, "{\"type\":\"pong\"}");
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.debug("Transport error on WebSocket {}: {}", session.getId(), exception.toString());
        registry.remove(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        registry.remove(session);
    }
}
