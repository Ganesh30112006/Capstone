package com.weatherplatform.iotstream.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.weatherplatform.iotstream.config.IotStreamProperties;
import com.weatherplatform.iotstream.dto.StreamEnvelope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LiveSessionRegistryTest {

    private IotStreamProperties properties;
    private LiveSessionRegistry registry;

    @BeforeEach
    void setUp() {
        properties = new IotStreamProperties();
        registry = new LiveSessionRegistry(new ObjectMapper().registerModule(new JavaTimeModule()), properties);
    }

    private WebSocketSession openSession(String id) {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn(id);
        when(session.isOpen()).thenReturn(true);
        return session;
    }

    private StreamEnvelope frame() {
        return StreamEnvelope.builder()
                .type(StreamEnvelope.Type.telemetry)
                .deviceUid("esp32-01")
                .receivedAt(Instant.now())
                .build();
    }

    @Test
    void registersAndRemovesSessions() {
        WebSocketSession a = openSession("a");
        assertThat(registry.register(a)).isTrue();
        assertThat(registry.size()).isEqualTo(1);

        registry.remove(a);
        assertThat(registry.size()).isZero();
    }

    @Test
    void rejectsSessionsBeyondCapacity() {
        properties.getWebSocket().setMaxSessions(1);
        assertThat(registry.register(openSession("a"))).isTrue();
        assertThat(registry.register(openSession("b"))).isFalse();
    }

    @Test
    void broadcastSendsToEveryOpenSession() throws Exception {
        WebSocketSession a = openSession("a");
        WebSocketSession b = openSession("b");
        registry.register(a);
        registry.register(b);

        registry.broadcast(frame());

        verify(a, atLeastOnce()).sendMessage(any(TextMessage.class));
        verify(b, atLeastOnce()).sendMessage(any(TextMessage.class));
    }

    @Test
    void aFailingSessionIsDroppedAndDoesNotBlockOthers() throws Exception {
        WebSocketSession bad = openSession("bad");
        WebSocketSession good = openSession("good");
        doThrow(new IOException("client gone")).when(bad).sendMessage(any());
        registry.register(bad);
        registry.register(good);

        registry.broadcast(frame());

        verify(good, atLeastOnce()).sendMessage(any(TextMessage.class));
        verify(bad).close(any(CloseStatus.class));
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void closedSessionsAreReapedOnBroadcast() throws Exception {
        WebSocketSession closed = mock(WebSocketSession.class);
        when(closed.getId()).thenReturn("closed");
        when(closed.isOpen()).thenReturn(false);
        registry.register(closed);

        registry.broadcast(frame());

        verify(closed, never()).sendMessage(any());
        assertThat(registry.size()).isZero();
    }

    @Test
    void broadcastWithNoSessionsIsANoOp() {
        registry.broadcast(frame()); // must not throw
        assertThat(registry.size()).isZero();
    }

    /**
     * Every write to a session must be serialised, whoever initiates it.
     *
     * The opening frame and the pong reply used to call
     * {@code session.sendMessage} directly, while the RabbitMQ fan-out thread
     * called it under a lock. Because a session is reachable by fan-out as soon
     * as it is registered, a client connecting while telemetry was flowing had
     * two threads writing to one endpoint: Tomcat saw TEXT_PARTIAL_WRITING and
     * failed the session, so under live traffic every connecting client was
     * disconnected immediately. An idle stream never triggered it.
     *
     * The mock session below fails if a second thread is ever inside
     * sendMessage while the first has not left.
     */
    @Test
    void everyWriteToASessionIsSerialised() throws Exception {
        AtomicInteger concurrent = new AtomicInteger();
        AtomicBoolean overlapped = new AtomicBoolean(false);

        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("racy");
        when(session.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            if (concurrent.incrementAndGet() > 1) {
                overlapped.set(true);
            }
            // Wide enough that an unsynchronised caller would land inside it.
            Thread.sleep(1);
            concurrent.decrementAndGet();
            return null;
        }).when(session).sendMessage(any(TextMessage.class));

        registry.register(session);

        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(3);
        // One thread per caller that writes to a session: fan-out, the opening
        // frame, and the keep-alive reply.
        Runnable broadcaster = writer(start, done, () -> registry.broadcast(frame()));
        Runnable greeter = writer(start, done, () -> registry.sendTo(session, frame()));
        Runnable ponger = writer(start, done, () -> registry.sendTo(session, "{\"type\":\"pong\"}"));
        pool.submit(broadcaster);
        pool.submit(greeter);
        pool.submit(ponger);

        start.countDown();
        assertThat(done.await(20, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(overlapped).isFalse();
    }

    private Runnable writer(CountDownLatch start, CountDownLatch done, Runnable body) {
        return () -> {
            try {
                start.await();
                for (int i = 0; i < 40; i++) {
                    body.run();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        };
    }

}
