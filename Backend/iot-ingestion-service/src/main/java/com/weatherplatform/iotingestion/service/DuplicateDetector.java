package com.weatherplatform.iotingestion.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Short-window suppression of obviously repeated messages.
 *
 * This is an optimisation, not the platform's correctness guarantee. It is
 * per-instance and cleared on restart, so with several ingestion replicas the
 * same message can still pass through twice. That is safe: sensor-service
 * enforces real idempotency with a unique constraint on
 * {@code sensor_data.message_id}, which survives restarts and holds across
 * every replica.
 *
 * Its job here is simply to keep obvious SQS redeliveries from doing needless
 * work downstream.
 */
@Slf4j
@Service
public class DuplicateDetector {

    private final Map<String, Instant> seenMessages = new ConcurrentHashMap<>();
    private final ScheduledExecutorService cleaner;
    private final Duration retention;

    /** Bound on entries held, so a flood of unique IDs cannot exhaust the heap. */
    private final int maxEntries;

    public DuplicateDetector(
            @Value("${iot.dedupe.retention-seconds:300}") long retentionSeconds,
            @Value("${iot.dedupe.max-entries:100000}") int maxEntries) {

        this.retention = Duration.ofSeconds(retentionSeconds);
        this.maxEntries = maxEntries;

        this.cleaner = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "iot-dedupe-cleanup");
            t.setDaemon(true);
            return t;
        });
        this.cleaner.scheduleAtFixedRate(this::cleanup, 1, 1, TimeUnit.MINUTES);
    }

    public boolean isDuplicate(String messageId) {
        if (messageId == null || messageId.isBlank()) {
            return false;
        }

        // Shed the cache rather than run out of memory; correctness is unaffected
        // because the database constraint is what actually deduplicates.
        if (seenMessages.size() >= maxEntries) {
            log.warn("Dedupe cache reached {} entries — clearing. "
                    + "Idempotency is still guaranteed by the database constraint.", maxEntries);
            seenMessages.clear();
        }

        return seenMessages.putIfAbsent(messageId, Instant.now()) != null;
    }

    /**
     * Releases a claim that {@link #isDuplicate(String)} made.
     *
     * <p>{@code isDuplicate} records the id at the moment it checks it, so from
     * that point on the caller owns the claim -- and owns it until the message
     * is safely on the bus. If the publish then fails, the message is returned
     * to SQS and redelivered; without this call the redelivery would match the
     * claim left behind by the attempt that failed, be counted as a duplicate
     * and acknowledged, discarding a reading that was never published.
     *
     * <p>That is not hypothetical. A sixty-second broker outage under live
     * traffic lost 248 messages this way, and the failure counter and the
     * duplicate counter ended on exactly the same number -- every interrupted
     * message was deleted by the very cache meant to be a harmless
     * optimisation. The database constraint cannot be the backstop here,
     * because a message dropped at this stage never reaches the database at
     * all.
     */
    public void forget(String messageId) {
        if (messageId != null && !messageId.isBlank()) {
            seenMessages.remove(messageId);
        }
    }

    private void cleanup() {
        try {
            Instant cutoff = Instant.now().minus(retention);
            seenMessages.entrySet().removeIf(entry -> entry.getValue().isBefore(cutoff));
        } catch (Exception e) {
            log.warn("Dedupe cache cleanup failed", e);
        }
    }

    /** Releases the cleanup thread so shutdown is not delayed. */
    @PreDestroy
    void shutdown() {
        cleaner.shutdownNow();
    }

    /** Visible for tests and for the cache-size gauge. */
    public int trackedCount() {
        return seenMessages.size();
    }
}
