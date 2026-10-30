package com.weatherplatform.iotstream.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Tracks connected browser clients and broadcasts frames to them.
 *
 * <p>Broadcast is fan-out-and-forget with per-session isolation: a send that
 * fails or a client that has fallen behind is closed and removed, and every
 * other client is unaffected. One slow browser must never back-pressure the
 * live stream.
 */
@Slf4j
@Component
public class LiveSessionRegistry {

    private final Map<String, Entry> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final IotStreamProperties properties;

    public LiveSessionRegistry(ObjectMapper objectMapper, IotStreamProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    private record Entry(WebSocketSession session, AtomicInteger inFlight) {}

    public boolean register(WebSocketSession session) {
        if (sessions.size() >= properties.getWebSocket().getMaxSessions()) {
            log.warn("Rejecting WebSocket session {}: capacity {} reached",
                    session.getId(), properties.getWebSocket().getMaxSessions());
            return false;
        }
        sessions.put(session.getId(), new Entry(session, new AtomicInteger()));
        log.info("WebSocket client {} connected ({} total)", session.getId(), sessions.size());
        return true;
    }

    public void remove(WebSocketSession session) {
        if (sessions.remove(session.getId()) != null) {
            log.info("WebSocket client {} disconnected ({} remain)", session.getId(), sessions.size());
        }
    }

    public int size() {
        return sessions.size();
    }

    /** Broadcasts one frame to every connected client. Never throws. */
    public void broadcast(StreamEnvelope envelope) {
        if (sessions.isEmpty()) {
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(envelope);
        } catch (Exception e) {
            log.error("Could not serialise stream frame; dropping it", e);
            return;
        }
        TextMessage message = new TextMessage(payload);
        for (Entry entry : sessions.values()) {
            sendOne(entry, message);
        }
    }

    /**
     * Sends one frame to one specific client.
     *
     * The connect handler's opening frame has to go through here rather than
     * calling {@code session.sendMessage} itself. The session becomes visible
     * to {@link #broadcast} the moment it is registered, so a direct write
     * raced the fan-out thread on the same endpoint and left it in
     * {@code TEXT_PARTIAL_WRITING} -- Tomcat then failed the session with an
     * IllegalStateException and the client was disconnected before it received
     * anything. Under live traffic that killed every connecting client; with an
     * idle stream it never happened, which is why it survived the unit tests.
     *
     * Every write to a session now goes through {@link #sendOne} and its lock.
     */
    public void sendTo(WebSocketSession session, StreamEnvelope envelope) {
        try {
            sendTo(session, objectMapper.writeValueAsString(envelope));
        } catch (Exception e) {
            log.error("Could not serialise frame for client {}; dropping it", session.getId(), e);
        }
    }

    /** As above, for a frame that is already serialised (the pong keep-alive). */
    public void sendTo(WebSocketSession session, String payload) {
        Entry entry = sessions.get(session.getId());
        if (entry == null) {
            return;
        }
        sendOne(entry, new TextMessage(payload));
    }

    /** Sends one frame to one session, closing it on failure or overrun. */
    private void sendOne(Entry entry, TextMessage message) {
        WebSocketSession session = entry.session();
        if (!session.isOpen()) {
            remove(session);
            return;
        }
        int pending = entry.inFlight().incrementAndGet();
        if (pending > properties.getWebSocket().getPerSessionBufferLimit()) {
            entry.inFlight().decrementAndGet();
            log.warn("WebSocket client {} is not keeping up ({} frames buffered); closing it",
                    session.getId(), pending);
            close(session, CloseStatus.SESSION_NOT_RELIABLE);
            return;
        }
        try {
            // WebSocketSession.sendMessage is not thread-safe; serialise per session.
            synchronized (session) {
                session.sendMessage(message);
            }
        } catch (IOException | IllegalStateException e) {
            log.debug("Send to WebSocket client {} failed: {}", session.getId(), e.toString());
            close(session, CloseStatus.SERVER_ERROR);
        } finally {
            entry.inFlight().decrementAndGet();
        }
    }

    private void close(WebSocketSession session, CloseStatus status) {
        remove(session);
        try {
            session.close(status);
        } catch (IOException ignored) {
            // Already gone.
        }
    }
}
