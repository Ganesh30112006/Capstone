package com.weatherplatform.analyticsservice.repository;

import com.weatherplatform.analyticsservice.entity.DailyStatistics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * Read access to the daily aggregate buckets.
 *
 * The write side deliberately lives in {@link DailyAggregateWriter} rather than
 * here: folding a reading into its bucket has to be one atomic statement, which
 * is a native upsert, and that does not belong on the interface every reader
 * depends on.
 */
@Repository
public interface DailyStatisticsRepository extends JpaRepository<DailyStatistics, Long> {

    Optional<DailyStatistics> findByDeviceUidAndStatDate(String deviceUid, LocalDate statDate);

    List<DailyStatistics> findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(
            String deviceUid, LocalDate from, LocalDate to);

    List<DailyStatistics> findByDeviceUidOrderByStatDateDesc(String deviceUid);

    List<DailyStatistics> findByStatDateBetweenOrderByStatDateAsc(LocalDate from, LocalDate to);

    List<DailyStatistics> findByStatDate(LocalDate statDate);
}
