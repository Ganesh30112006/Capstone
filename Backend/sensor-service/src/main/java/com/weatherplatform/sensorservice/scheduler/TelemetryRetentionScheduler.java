package com.weatherplatform.sensorservice.scheduler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Keeps sensor_data bounded.
 *
 * Raw telemetry grows forever if left alone. Completed hours are folded into
 * {@code sensor_data_hourly}, and only then are the raw rows past the retention
 * window deleted — the prune function refuses to delete an hour that has no
 * summary, so a failed roll-up can never destroy data.
 *
 * Both steps are idempotent, so a missed or repeated run is harmless.
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "sensor.retention.enabled", havingValue = "true", matchIfMissing = true)
public class TelemetryRetentionScheduler {

    @PersistenceContext
    private EntityManager entityManager;

    private final com.weatherplatform.sensorservice.service.SensorHealthLedger healthLedger;

    @Value("${sensor.retention.rollup-lag-hours:2}")
    private int rollupLagHours;

    @Value("${sensor.retention.rollup-window-hours:48}")
    private int rollupWindowHours;

    @Value("${sensor.retention.retain-days:14}")
    private int retainDays;

    /**
     * Folds completed hours into the summary table.
     *
     * Hourly rather than nightly, and separate from the prune, because the two
     * cost different things. The roll-up now reads a bounded window, so running
     * it often keeps each run small and keeps the summaries close behind the
     * raw rows. Nightly meant one run had to chew through a whole day of
     * readings — and since nothing is pruned until its hour is summarised, that
     * a day of data could not be pruned the moment it aged out.
     */
    @Scheduled(cron = "${sensor.retention.rollup-cron:0 10 * * * *}")
    @Transactional
    public void rollUp() {
        try {
            Object rolled = entityManager
                    .createNativeQuery("SELECT rollup_sensor_data_hourly(:lag, :window)")
                    .setParameter("lag", rollupLagHours)
                    .setParameter("window", rollupWindowHours)
                    .getSingleResult();
            log.info("Telemetry roll-up complete: {} hourly bucket(s) written", rolled);
        } catch (Exception e) {
            // Retention is housekeeping — it must never take the service down.
            // The next scheduled run picks up exactly where this one left off.
            log.error("Telemetry roll-up failed; will retry on the next schedule", e);
        }
    }

    /**
     * Deletes raw readings past the retention window — and only those whose
     * hour already has a summary. That guard lives in the SQL function, so a
     * roll-up that failed cannot turn into data that was deleted anyway.
     *
     * Still in the small hours: the delete takes row locks and should not
     * compete with ingestion.
     */
    @Scheduled(cron = "${sensor.retention.prune-cron:0 30 3 * * *}")
    @Transactional
    public void prune() {
        try {
            Object pruned = entityManager
                    .createNativeQuery("SELECT prune_sensor_data(:days)")
                    .setParameter("days", retainDays)
                    .getSingleResult();
            log.info("Telemetry prune complete: {} raw reading(s) older than {} days removed", pruned, retainDays);

            // The health ledger only ever feeds a 24-hour score, so it is kept for
            // the same span as raw telemetry and no longer.
            int buckets = healthLedger.pruneBefore(java.time.LocalDateTime.now().minusDays(retainDays));
            log.info("Sensor health ledger prune complete: {} hourly bucket(s) removed", buckets);
        } catch (Exception e) {
            log.error("Telemetry prune failed; will retry on the next schedule", e);
        }
    }
}
