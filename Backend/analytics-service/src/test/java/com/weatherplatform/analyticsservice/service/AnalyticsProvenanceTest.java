package com.weatherplatform.analyticsservice.service;

import com.weatherplatform.analyticsservice.dto.EnvironmentalReportResponse;
import com.weatherplatform.analyticsservice.dto.FleetReportResponse;
import com.weatherplatform.analyticsservice.dto.NetworkTrendResponse;
import com.weatherplatform.analyticsservice.entity.DailyStatistics;
import com.weatherplatform.analyticsservice.entity.DeviceLocationSnapshot;
import com.weatherplatform.analyticsservice.event.SensorReadingEvent;
import com.weatherplatform.analyticsservice.mapper.StatisticsMapper;
import com.weatherplatform.analyticsservice.repository.DailyAggregateWriter;
import com.weatherplatform.analyticsservice.repository.DailyStatisticsRepository;
import com.weatherplatform.analyticsservice.repository.DeviceLocationSnapshotRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Every figure this service reports must be traceable to a recorded reading.
 *
 * Three things used to break that. The network trend synthesised its own
 * series from a hardcoded diurnal curve and fell back to a plausible 18 C day
 * against an empty database. Device reports returned a literal 99.5% uptime.
 * And the fleet rollup divided a sum of non-null station averages by the total
 * station count, so every absent metric quietly dragged the fleet average
 * toward zero -- while an absent air-quality series reported an AQI of 0.
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsProvenanceTest {

    @Mock
    private DailyStatisticsRepository repository;
    @Mock
    private DeviceLocationSnapshotRepository snapshotRepository;
    @Mock
    private DailyAggregateWriter aggregateWriter;
    @Mock
    private StatisticsMapper mapper;

    @InjectMocks
    private AnalyticsServiceImpl analyticsService;

    /**
     * A row in the shape the database actually produces.
     *
     * <p>The sum columns are NOT NULL DEFAULT 0 since
     * V2__daily_statistics_aggregate_invariants, and DailyAggregateWriter has
     * always written them through COALESCE(..., 0), so a null sum cannot reach
     * the read path from either direction. Building test rows with null sums
     * modelled a database state that cannot occur, which is how a null-tolerant
     * sum went on looking necessary after it had stopped being so.
     *
     * <p>A caller passing null here means "this metric was not reported", which
     * on disk is a zero sum -- and that distinction is exactly what the schema
     * cannot currently express. See the air-quality test below.
     */
    private static DailyStatistics row(String uid, LocalDate date, int readings, Double sumTemp, Long sumAq) {
        // A null sum here means "this station did not report the metric", which
        // on disk is a zero sum and a zero count -- never a null sum, because
        // DailyAggregateWriter writes them through COALESCE(..., 0) and since V2
        // the columns are NOT NULL. Building rows with null sums modelled a
        // database state that cannot occur, which is how a null-tolerant sum and
        // a "did we see one?" flag both went on looking necessary long after
        // they had stopped working.
        return DailyStatistics.builder()
                .deviceUid(uid)
                .statDate(date)
                .readingCount(readings)
                .sumTemperature(sumTemp == null ? BigDecimal.ZERO : BigDecimal.valueOf(sumTemp))
                .temperatureCount(sumTemp == null ? 0 : readings)
                .sumHumidity(BigDecimal.ZERO)
                .humidityCount(0)
                .sumAirQuality(sumAq == null ? 0L : sumAq)
                .airQualityCount(sumAq == null ? 0 : readings)
                .sumPressure(BigDecimal.ZERO)
                .pressureCount(0)
                .sumOxygen(BigDecimal.ZERO)
                .oxygenCount(0)
                .rainEventsCount(0)
                .build();
    }

    // ------------------------------------------------------------- trend

    @Test
    void networkTrend_withNoStatistics_inventsNothing() {
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(Collections.emptyList());

        List<NetworkTrendResponse> trend = analyticsService.getNetworkTrend();

        // The old implementation returned seven fabricated points here, based
        // on a fallback of 18.0 C / 60% / 22 AQI / 20.9% / 1013 hPa.
        assertTrue(trend.isEmpty(), "an empty database must produce an empty series, not a plausible day");
    }

    @Test
    void networkTrend_weightsEachDayByReadingCount() {
        LocalDate day = LocalDate.now();
        // Two stations on the same day: one reported 90 times averaging 10 C,
        // the other 10 times averaging 30 C. The fleet mean is 12 C, not 20 C.
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(
                        row("STN-A", day, 90, 900.0, null),
                        row("STN-B", day, 10, 300.0, null)));

        List<NetworkTrendResponse> trend = analyticsService.getNetworkTrend();

        assertEquals(1, trend.size());
        assertEquals(0, BigDecimal.valueOf(12.00).compareTo(trend.get(0).getTemperature()));
        // Nothing recorded air quality, so nothing is claimed about it.
        assertNull(trend.get(0).getAirQuality());
    }

    @Test
    void networkTrend_labelsPointsWithRealDates() {
        LocalDate day = LocalDate.now().minusDays(2);
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(row("STN-A", day, 4, 80.0, null)));

        List<NetworkTrendResponse> trend = analyticsService.getNetworkTrend();

        // Was "00:00", "04:00", ... "Now" -- labels with no date behind them.
        assertEquals(day.toString(), trend.get(0).getTimestamp());
    }

    // ------------------------------------------------------------ uptime

    @Test
    void deviceReport_doesNotClaimAnUptimeItCannotMeasure() {
        LocalDate today = LocalDate.now();
        when(repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(any(), any(), any()))
                .thenReturn(List.of(row("STN-A", today, 12, 240.0, 264L)));

        EnvironmentalReportResponse report = analyticsService.getDailyReport("STN-A", today);

        // Was a literal 99.5.
        assertNull(report.getUptimePercentage());
        assertEquals(12, report.getTotalReadings());
    }

    @Test
    void deviceReport_withNoData_reportsUnknownUptimeRatherThanZero() {
        when(repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(any(), any(), any()))
                .thenReturn(Collections.emptyList());

        EnvironmentalReportResponse report = analyticsService.getDailyReport("STN-A", LocalDate.now());

        // 0.0 asserted a total outage; nothing here established one.
        assertNull(report.getUptimePercentage());
    }

    // ------------------------------------------------------- fleet rollup

    @Test
    void fleetReport_doesNotDepressAveragesWithStationsThatHaveNoMetric() {
        LocalDate today = LocalDate.now();
        // Two stations reported temperature, one did not. The old code summed
        // the two present averages and divided by three.
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(
                        row("STN-A", today, 10, 200.0, null),
                        row("STN-B", today, 10, 200.0, null),
                        row("STN-C", today, 0, null, null)));

        FleetReportResponse fleet = analyticsService.getFleetDailyReport(today);

        // 400 over 20 readings = 20.0. Dividing by the station count gave 13.33.
        assertEquals(0, BigDecimal.valueOf(20.00).compareTo(fleet.getFleetAvgTemperature()));
        assertEquals(3, fleet.getTotalStations());
        assertEquals(20, fleet.getTotalReadings());
    }

    @Test
    void fleetReport_withNoAirQualityReportsUnknownRatherThanPerfectAir() {
        LocalDate today = LocalDate.now();
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(row("STN-A", today, 5, 100.0, null)));

        FleetReportResponse fleet = analyticsService.getFleetDailyReport(today);

        // `.orElse(0.0)` reported an average AQI of zero -- perfect air, from
        // no measurements at all.
        assertNull(fleet.getFleetAvgAirQuality());
    }

    @Test
    void fleetReport_withRowsButNoReadings_reportsUnknownRatherThanZero() {
        LocalDate today = LocalDate.now();
        // The aggregate rows exist -- the schema defaults every sum to 0 -- but
        // no reading was ever folded into them, so reading_count is 0. This is
        // what a freshly migrated or freshly created bucket looks like.
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(
                        row("STN-A", today, 0, 0.0, 0L),
                        row("STN-B", today, 0, 0.0, 0L)));

        FleetReportResponse fleet = analyticsService.getFleetDailyReport(today);

        // Dividing by zero readings has no answer. Reporting 0.00 would state a
        // fleet temperature of zero degrees and an AQI of zero -- both derived
        // from no measurement at all, which is the exact shape of the bug the
        // `.orElse(0.0)` above used to have.
        assertEquals(0, fleet.getTotalReadings());
        assertNull(fleet.getFleetAvgTemperature());
        assertNull(fleet.getFleetAvgAirQuality());
        assertNull(fleet.getFleetAvgHumidity());
        assertNull(fleet.getFleetAvgPressure());
        assertNull(fleet.getFleetAvgOxygen());
        // The stations themselves are still reported: they exist, and that is
        // known. Only the averages are unknown.
        assertEquals(2, fleet.getTotalStations());
    }

    @Test
    void deviceReport_withRowsButNoReadings_reportsUnknownAveragesRatherThanZero() {
        LocalDate today = LocalDate.now();
        when(repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(any(), any(), any()))
                .thenReturn(List.of(row("STN-A", today, 0, 0.0, 0L)));

        EnvironmentalReportResponse report = analyticsService.getDailyReport("STN-A", today);

        // Same division, on the per-device path.
        assertEquals(0, report.getTotalReadings());
        assertNull(report.getAvgTemperature());
        assertNull(report.getAvgHumidity());
        assertNull(report.getAvgPressure());
        assertNull(report.getAvgOxygen());
    }

    @Test
    void fleetReport_doesNotRequeryPerStation() {
        LocalDate today = LocalDate.now();
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(
                        row("STN-A", today, 4, 80.0, 88L),
                        row("STN-B", today, 4, 80.0, 88L)));

        analyticsService.getFleetDailyReport(today);

        // The rows are already loaded and grouped; the old loop fetched each
        // station again, turning one query into 1 + N.
        verify(repository, never()).findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(any(), any(), any());
    }

    // ----------------------------------------------------------- heatmap

    @Test
    void heatmap_rejectsAnUnknownParameterInsteadOfMislabellingTemperature() {
        // `?parameter=ozone` used to return temperatures stamped "ozone".
        assertThrows(IllegalArgumentException.class,
                () -> analyticsService.getHeatmap("ozone", null, null, null));
    }

    @Test
    void heatmap_stillServesSupportedParameters() {
        when(snapshotRepository.findAll()).thenReturn(Collections.emptyList());

        assertEquals("humidity", analyticsService.getHeatmap("humidity", null, null, null).getParameter());
    }

    // ------------------------------------------------------- ingestion path

    @Test
    void processSensorReading_foldsAtomicallyInsteadOfReadModifyWrite() {
        SensorReadingEvent event = SensorReadingEvent.builder()
                .deviceUid("STN-A")
                .recordedAt(LocalDateTime.of(2026, 5, 14, 10, 30))
                .temperature(BigDecimal.valueOf(21.5))
                .humidity(BigDecimal.valueOf(60))
                .airQuality(42)
                .rain(Boolean.TRUE)
                .build();

        analyticsService.processSensorReading(event);

        /*
         * The aggregation must reach the database as one statement. Reading the
         * row first and writing a computed total back loses one of two readings
         * that arrive for the same device and day at the same moment, and does
         * so silently -- no constraint fires, so nothing retries.
         */
        verify(aggregateWriter).foldReadingIntoDailyBucket(
                eq("STN-A"),
                eq(LocalDate.of(2026, 5, 14)),
                eq(BigDecimal.valueOf(21.5)),
                eq(BigDecimal.valueOf(60)),
                eq(42),
                eq(null),
                eq(null),
                eq(1));
        verify(repository, never()).findByDeviceUidAndStatDate(any(), any());
        verify(repository, never()).save(any());
        verifyNoMoreInteractions(repository);
    }

    @Test
    void processSensorReading_countsANonRainyReadingAsZeroRainEvents() {
        SensorReadingEvent event = SensorReadingEvent.builder()
                .deviceUid("STN-A")
                .recordedAt(LocalDateTime.of(2026, 5, 14, 10, 30))
                .rain(Boolean.FALSE)
                .build();

        analyticsService.processSensorReading(event);

        verify(aggregateWriter).foldReadingIntoDailyBucket(
                any(), any(), any(), any(), any(), any(), any(), eq(0));
    }

    @Test
    void heatmap_withARadius_asksTheDatabaseForABoxRatherThanTheWholeTable() {
        when(snapshotRepository.findByLatitudeBetweenAndLongitudeBetween(
                anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(Collections.emptyList());

        analyticsService.getHeatmap("temperature", 17.385, 78.4867, 50.0);

        // Haversine cannot use an index; the enclosing box can, and
        // idx_location_snapshots_coords covers (latitude, longitude).
        verify(snapshotRepository).findByLatitudeBetweenAndLongitudeBetween(
                anyDouble(), anyDouble(), anyDouble(), anyDouble());
        verify(snapshotRepository, never()).findAll();
    }

    @Test
    void heatmap_boxIsWideEnoughToKeepEveryPointInsideTheRadius() {
        // A station due east of the centre, comfortably inside 50 km. If the
        // box were computed with degrees of latitude in both directions it
        // would be too narrow at this latitude and would drop the point.
        DeviceLocationSnapshot eastward = DeviceLocationSnapshot.builder()
                .deviceUid("STN-E")
                .latitude(17.385)
                .longitude(78.4867 + 0.40)
                .latestTemperature(BigDecimal.valueOf(31.2))
                .build();
        when(snapshotRepository.findByLatitudeBetweenAndLongitudeBetween(
                anyDouble(), anyDouble(), anyDouble(), anyDouble()))
                .thenReturn(List.of(eastward));

        var heatmap = analyticsService.getHeatmap("temperature", 17.385, 78.4867, 50.0);

        assertEquals(1, heatmap.getPoints().size());
    }

    @Test
    void heatmap_withoutARadiusStillReadsEverySnapshot() {
        when(snapshotRepository.findAll()).thenReturn(Collections.emptyList());

        analyticsService.getHeatmap("temperature", null, null, null);

        verify(snapshotRepository).findAll();
    }

    // ------------------------------------- per-metric counts (V3)

    /** A row where a metric was measured on only some of the day's readings. */
    private static DailyStatistics partialRow(
            String uid, LocalDate date, int readings, double sumTemp, int tempCount) {
        return DailyStatistics.builder()
                .deviceUid(uid).statDate(date).readingCount(readings)
                .sumTemperature(BigDecimal.valueOf(sumTemp)).temperatureCount(tempCount)
                .sumHumidity(BigDecimal.ZERO).humidityCount(0)
                .sumAirQuality(0L).airQualityCount(0)
                .sumPressure(BigDecimal.ZERO).pressureCount(0)
                .sumOxygen(BigDecimal.ZERO).oxygenCount(0)
                .rainEventsCount(0)
                .build();
    }

    @Test
    void fleetAverage_dividesByTheReadingsThatCarriedTheMetric() {
        LocalDate today = LocalDate.now();
        // 100 readings, but the thermometer only reported on 10 of them.
        // 200 over 10 is 20.0; over all 100 it would read 2.0 -- a temperature
        // no station ever recorded, produced entirely by the divisor.
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(partialRow("STN-A", today, 100, 200.0, 10)));

        FleetReportResponse fleet = analyticsService.getFleetDailyReport(today);

        assertEquals(0, BigDecimal.valueOf(20.00).compareTo(fleet.getFleetAvgTemperature()));
    }

    @Test
    void fleetAverage_isUnknownWhenAMetricWasNeverMeasured() {
        LocalDate today = LocalDate.now();
        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(partialRow("STN-A", today, 50, 0.0, 0)));

        FleetReportResponse fleet = analyticsService.getFleetDailyReport(today);

        // A zero sum over zero measurements is not a reading of zero.
        assertNull(fleet.getFleetAvgTemperature());
    }

    @Test
    void fleetAverage_isUnknownWhenTheCountIsUnknown() {
        LocalDate today = LocalDate.now();
        // A row written before V3: the sum survived, the count did not.
        DailyStatistics legacy = partialRow("STN-OLD", today, 40, 800.0, 10);
        legacy.setTemperatureCount(null);

        when(repository.findByStatDateBetweenOrderByStatDateAsc(any(), any()))
                .thenReturn(List.of(legacy, partialRow("STN-NEW", today, 10, 200.0, 10)));

        FleetReportResponse fleet = analyticsService.getFleetDailyReport(today);

        // Dividing the full numerator by only the known part of the denominator
        // would report a confidently wrong number. Unknown is the honest answer.
        assertNull(fleet.getFleetAvgTemperature());
    }
}
