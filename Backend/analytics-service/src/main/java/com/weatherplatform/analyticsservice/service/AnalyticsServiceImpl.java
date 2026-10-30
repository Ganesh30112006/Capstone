package com.weatherplatform.analyticsservice.service;

import com.weatherplatform.analyticsservice.dto.DailyStatisticsResponse;
import com.weatherplatform.analyticsservice.dto.EnvironmentalReportResponse;
import com.weatherplatform.analyticsservice.dto.FleetReportResponse;
import com.weatherplatform.analyticsservice.dto.HeatmapPointDTO;
import com.weatherplatform.analyticsservice.dto.HeatmapResponse;
import com.weatherplatform.analyticsservice.dto.NetworkTrendResponse;
import com.weatherplatform.analyticsservice.entity.DailyStatistics;
import com.weatherplatform.analyticsservice.entity.DeviceLocationSnapshot;
import com.weatherplatform.analyticsservice.event.SensorReadingEvent;
import com.weatherplatform.analyticsservice.exception.ResourceNotFoundException;
import com.weatherplatform.analyticsservice.mapper.StatisticsMapper;
import com.weatherplatform.analyticsservice.repository.DailyAggregateWriter;
import com.weatherplatform.analyticsservice.repository.DailyStatisticsRepository;
import com.weatherplatform.analyticsservice.repository.DeviceLocationSnapshotRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AnalyticsServiceImpl implements AnalyticsService {

    private static final double KM_PER_DEGREE_LATITUDE = 111.32;

    /** Days of fleet history returned by the network trend series. */
    private static final int TREND_DAYS = 7;

    private static final Set<String> SUPPORTED_HEATMAP_PARAMETERS =
            Set.of("temperature", "humidity", "airquality", "air_quality", "oxygen", "pressure");

    private final DailyStatisticsRepository repository;
    private final DailyAggregateWriter aggregateWriter;
    private final DeviceLocationSnapshotRepository snapshotRepository;
    private final StatisticsMapper mapper;

    @Override
    @Transactional
    public void processSensorReading(SensorReadingEvent event) {
        LocalDate date = event.getRecordedAt() != null ? event.getRecordedAt().toLocalDate() : LocalDate.now();
        log.debug("Processing reading for device {} on {}", event.getDeviceUid(), date);

        /*
         * One statement, one row lock, no lost updates.
         *
         * This was a read-modify-write: SELECT the day's row, add the reading
         * to it in Java, save. Two readings for the same device and day landing
         * concurrently each read the same starting totals and each wrote back
         * their own version, so whichever committed second discarded the
         * other's contribution entirely -- the reading vanished from both the
         * sum and reading_count, and no error was raised to retry on. Nor could
         * a retry have helped: the arithmetic had already been done against
         * stale values.
         *
         * The aggregation now happens inside the database, where the row lock
         * covers the whole read-add-write, so concurrent readings serialise.
         */
        aggregateWriter.foldReadingIntoDailyBucket(
                event.getDeviceUid(),
                date,
                event.getTemperature(),
                event.getHumidity(),
                event.getAirQuality(),
                event.getPressure(),
                event.getOxygen(),
                Boolean.TRUE.equals(event.getRain()) ? 1 : 0);

        // Update Geo Snapshot for Heatmap
        if (event.getLatitude() != null && event.getLongitude() != null) {
            DeviceLocationSnapshot snapshot = snapshotRepository.findByDeviceUid(event.getDeviceUid())
                    .orElse(DeviceLocationSnapshot.builder()
                            .deviceUid(event.getDeviceUid())
                            .build());

            snapshot.setLatitude(event.getLatitude());
            snapshot.setLongitude(event.getLongitude());
            snapshot.setLatestTemperature(event.getTemperature());
            snapshot.setLatestHumidity(event.getHumidity());
            snapshot.setLatestAirQuality(event.getAirQuality());
            snapshot.setLatestPressure(event.getPressure());
            snapshot.setLatestOxygen(event.getOxygen());
            snapshotRepository.save(snapshot);
        }
    }

    @Override
    public List<DailyStatisticsResponse> getDailyStatistics(String deviceUid, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().minusDays(30);
        LocalDate end = to != null ? to : LocalDate.now();
        return repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(deviceUid, start, end)
                .stream().map(mapper::toResponse).toList();
    }

    @Override
    public List<DailyStatisticsResponse> getWeeklyStatistics(String deviceUid) {
        LocalDate now = LocalDate.now();
        return repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(deviceUid, now.minusDays(6), now)
                .stream().map(mapper::toResponse).toList();
    }

    @Override
    public List<DailyStatisticsResponse> getMonthlyStatistics(String deviceUid) {
        LocalDate now = LocalDate.now();
        return repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(deviceUid, now.minusDays(29), now)
                .stream().map(mapper::toResponse).toList();
    }

    @Override
    public HeatmapResponse getHeatmap(String parameter, Double centerLat, Double centerLon, Double radiusKm) {
        String param = parameter != null ? parameter.toLowerCase() : "temperature";
        /*
         * The switch below fell through to temperature for anything it did
         * not recognise, while the response was still stamped with whatever
         * the caller asked for. `?parameter=ozone` therefore returned
         * temperatures labelled "ozone" -- a mislabelled series, which is
         * more misleading than an error, because nothing downstream can
         * tell that it is wrong.
         */
        if (!SUPPORTED_HEATMAP_PARAMETERS.contains(param)) {
            throw new IllegalArgumentException(
                    "Unsupported heatmap parameter: " + parameter + ". Supported: " + SUPPORTED_HEATMAP_PARAMETERS);
        }
        /*
         * A bounded query asks the database for a box first.
         *
         * This used to be findAll() followed by a Haversine distance per row in
         * Java, so a radius search over a thousand stations loaded a thousand
         * rows to keep a handful. Haversine is not indexable, but the enclosing
         * box is: idx_location_snapshots_coords covers (latitude, longitude),
         * so the box narrows the candidates and the exact distance check below
         * only runs over the survivors. The box can admit a corner point that
         * the radius excludes, which is why the exact test stays -- it never
         * excludes a point that belongs.
         */
        boolean bounded = centerLat != null && centerLon != null && radiusKm != null && radiusKm > 0;
        List<DeviceLocationSnapshot> snapshots = bounded
                ? snapshotRepository.findByLatitudeBetweenAndLongitudeBetween(
                        centerLat - degreesOfLatitude(radiusKm),
                        centerLat + degreesOfLatitude(radiusKm),
                        centerLon - degreesOfLongitude(radiusKm, centerLat),
                        centerLon + degreesOfLongitude(radiusKm, centerLat))
                : snapshotRepository.findAll();
        List<HeatmapPointDTO> points = new ArrayList<>();

        for (DeviceLocationSnapshot snap : snapshots) {
            if (snap.getLatitude() == null || snap.getLongitude() == null) continue;

            if (bounded) {
                double distance = calculateHaversineDistance(centerLat, centerLon, snap.getLatitude(), snap.getLongitude());
                if (distance > radiusKm) continue;
            }

            BigDecimal val = switch (param) {
                case "humidity" -> snap.getLatestHumidity();
                case "airquality", "air_quality" -> snap.getLatestAirQuality() != null ? BigDecimal.valueOf(snap.getLatestAirQuality()) : null;
                case "oxygen" -> snap.getLatestOxygen();
                case "pressure" -> snap.getLatestPressure();
                default -> snap.getLatestTemperature();
            };

            if (val != null) {
                points.add(HeatmapPointDTO.builder()
                        .deviceUid(snap.getDeviceUid())
                        .latitude(snap.getLatitude())
                        .longitude(snap.getLongitude())
                        .value(val)
                        .build());
            }
        }

        return HeatmapResponse.builder()
                .parameter(param)
                .timestamp(LocalDateTime.now())
                .points(points)
                .build();
    }

    @Override
    public List<NetworkTrendResponse> getNetworkTrend() {
        /*
         * This method used to synthesise its own data, and said so: "Generating
         * hourly mockup trend based on latest fleet averages for seamless
         * frontend integration". It took today's fleet average and applied a
         * fixed diurnal curve -- tempOffsets {-3.5, -4.0, -1.0, 3.2, 4.5, 0.5,
         * 1.2} and friends -- to invent seven points labelled 00:00 through
         * "Now". None of them were measurements. Oxygen and pressure were
         * repeated unchanged across all seven, and against an empty database
         * the series fell back to a plausible 18.0 C / 60% / 22 AQI day. Every
         * chart in the console plotted that curve under the heading
         * "NETWORK TELEMETRY".
         *
         * analytics-service stores daily aggregates only (DailyStatistics) and
         * has no intra-day table, so an honest hourly series cannot be built
         * here at all -- the raw rows live in sensor-service. Rather than
         * fabricate a resolution it does not have, this returns the real fleet
         * daily series it does have, one point per day, weighted by reading
         * count. When nothing was recorded it returns nothing.
         */
        LocalDate today = LocalDate.now();
        LocalDate from = today.minusDays(TREND_DAYS - 1L);

        Map<LocalDate, List<DailyStatistics>> byDate = repository
                .findByStatDateBetweenOrderByStatDateAsc(from, today)
                .stream()
                .collect(Collectors.groupingBy(DailyStatistics::getStatDate));

        return byDate.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    List<DailyStatistics> dayStats = entry.getValue();
                    int readings = sumReadings(dayStats);
                    return NetworkTrendResponse.builder()
                            .timestamp(entry.getKey().toString())
                            .temperature(weightedAverage(dayStats, DailyStatistics::getSumTemperature, DailyStatistics::getTemperatureCount))
                            .humidity(weightedAverage(dayStats, DailyStatistics::getSumHumidity, DailyStatistics::getHumidityCount))
                            .airQuality(weightedAirQuality(dayStats))
                            .oxygen(weightedAverage(dayStats, DailyStatistics::getSumOxygen, DailyStatistics::getOxygenCount))
                            .pressure(weightedAverage(dayStats, DailyStatistics::getSumPressure, DailyStatistics::getPressureCount))
                            .build();
                })
                .toList();
    }

    @Override
    public EnvironmentalReportResponse getDailyReport(String deviceUid, LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        return generateReport(deviceUid, "DAILY", target, target);
    }

    @Override
    public EnvironmentalReportResponse getWeeklyReport(String deviceUid, LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = end.minusDays(6);
        return generateReport(deviceUid, "WEEKLY", start, end);
    }

    @Override
    public EnvironmentalReportResponse getMonthlyReport(String deviceUid, LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = end.minusDays(29);
        return generateReport(deviceUid, "MONTHLY", start, end);
    }

    @Override
    public EnvironmentalReportResponse getCustomReport(String deviceUid, LocalDate from, LocalDate to) {
        LocalDate start = from != null ? from : LocalDate.now().minusDays(7);
        LocalDate end = to != null ? to : LocalDate.now();
        return generateReport(deviceUid, "CUSTOM", start, end);
    }

    @Override
    public FleetReportResponse getFleetDailyReport(LocalDate date) {
        LocalDate target = date != null ? date : LocalDate.now();
        return generateFleetReport("FLEET_DAILY", target, target);
    }

    @Override
    public FleetReportResponse getFleetWeeklyReport(LocalDate endDate) {
        LocalDate end = endDate != null ? endDate : LocalDate.now();
        LocalDate start = end.minusDays(6);
        return generateFleetReport("FLEET_WEEKLY", start, end);
    }

    private EnvironmentalReportResponse generateReport(String deviceUid, String type, LocalDate from, LocalDate to) {
        return summarise(deviceUid, type, from, to,
                repository.findByDeviceUidAndStatDateBetweenOrderByStatDateAsc(deviceUid, from, to));
    }

    /** Rolls a station's already-loaded daily rows into one report. */
    private EnvironmentalReportResponse summarise(
            String deviceUid, String type, LocalDate from, LocalDate to, List<DailyStatistics> list) {

        if (list.isEmpty()) {
            return EnvironmentalReportResponse.builder()
                    .deviceUid(deviceUid)
                    .reportType(type)
                    .startDate(from)
                    .endDate(to)
                    .totalReadings(0)
                    // Not 0.0: a station with no statistics has unknown
                    // uptime, not zero uptime. Reporting 0% asserts a total
                    // outage that nothing here has established.
                    .uptimePercentage(null)
                    .build();
        }

        /*
         * The sum_* columns were nullable once, and a row written with a NULL --
         * by a backfill, or by any writer that was not this service -- went
         * straight into `reduce(ZERO, BigDecimal::add)` and threw on the null
         * operand, returning 500 from both the device report and the fleet
         * report. The application carried a null-tolerant sum for that.
         *
         * V2__daily_statistics_aggregate_invariants now backfills those rows and
         * holds the columns NOT NULL DEFAULT 0, and DailyStatisticsInvariantIT
         * proves it on a database migrated from V1 with the legacy NULL rows
         * still in it. The guard is gone, so a null here would surface as a
         * failure rather than being quietly absorbed into a total that looks
         * like a measurement.
         *
         * The min/max reductions below still filter nulls, and must: those
         * columns are genuinely optional and stay nullable.
         */
        int totalReadings = sumReadings(list);
        int rainEvents = list.stream().mapToInt(d -> d.getRainEventsCount() != null ? d.getRainEventsCount() : 0).sum();

        BigDecimal sumTemp = sumColumn(list, DailyStatistics::getSumTemperature);
        BigDecimal minTemp = list.stream().map(DailyStatistics::getMinTemperature).filter(v -> v != null).min(BigDecimal::compareTo).orElse(null);
        BigDecimal maxTemp = list.stream().map(DailyStatistics::getMaxTemperature).filter(v -> v != null).max(BigDecimal::compareTo).orElse(null);

        BigDecimal sumHum = sumColumn(list, DailyStatistics::getSumHumidity);
        BigDecimal minHum = list.stream().map(DailyStatistics::getMinHumidity).filter(v -> v != null).min(BigDecimal::compareTo).orElse(null);
        BigDecimal maxHum = list.stream().map(DailyStatistics::getMaxHumidity).filter(v -> v != null).max(BigDecimal::compareTo).orElse(null);

        long sumAq = list.stream().mapToLong(d -> d.getSumAirQuality() != null ? d.getSumAirQuality() : 0L).sum();
        Integer minAq = list.stream().map(DailyStatistics::getMinAirQuality).filter(v -> v != null).min(Integer::compareTo).orElse(null);
        Integer maxAq = list.stream().map(DailyStatistics::getMaxAirQuality).filter(v -> v != null).max(Integer::compareTo).orElse(null);

        BigDecimal sumPress = sumColumn(list, DailyStatistics::getSumPressure);
        BigDecimal minPress = list.stream().map(DailyStatistics::getMinPressure).filter(v -> v != null).min(BigDecimal::compareTo).orElse(null);
        BigDecimal maxPress = list.stream().map(DailyStatistics::getMaxPressure).filter(v -> v != null).max(BigDecimal::compareTo).orElse(null);

        BigDecimal sumOx = sumColumn(list, DailyStatistics::getSumOxygen);
        BigDecimal minOx = list.stream().map(DailyStatistics::getMinOxygen).filter(v -> v != null).min(BigDecimal::compareTo).orElse(null);
        BigDecimal maxOx = list.stream().map(DailyStatistics::getMaxOxygen).filter(v -> v != null).max(BigDecimal::compareTo).orElse(null);

        return EnvironmentalReportResponse.builder()
                .deviceUid(deviceUid)
                .reportType(type)
                .startDate(from)
                .endDate(to)
                .totalReadings(totalReadings)
                /*
                 * Was `totalReadings > 0 ? 99.5 : 0.0` -- a literal,
                 * returned on every device report as though measured. Real
                 * uptime needs readings-received over readings-expected,
                 * and the expected count depends on each station's
                 * reporting interval, which this service does not record.
                 * Until it does, uptime is unknown and says so.
                 */
                .uptimePercentage(null)
                .minTemperature(minTemp)
                .maxTemperature(maxTemp)
                .avgTemperature(divide(sumTemp, totalReadings))
                .minHumidity(minHum)
                .maxHumidity(maxHum)
                .avgHumidity(divide(sumHum, totalReadings))
                .minAirQuality(minAq)
                .maxAirQuality(maxAq)
                .avgAirQuality(totalReadings > 0 ? (double) sumAq / totalReadings : null)
                .minPressure(minPress)
                .maxPressure(maxPress)
                .avgPressure(divide(sumPress, totalReadings))
                .minOxygen(minOx)
                .maxOxygen(maxOx)
                .avgOxygen(divide(sumOx, totalReadings))
                .rainEvents(rainEvents)
                .build();
    }

    private FleetReportResponse generateFleetReport(String type, LocalDate from, LocalDate to) {
        List<DailyStatistics> allStats = repository.findByStatDateBetweenOrderByStatDateAsc(from, to);
        Map<String, List<DailyStatistics>> byDevice = allStats.stream()
                .collect(Collectors.groupingBy(DailyStatistics::getDeviceUid));

        /*
         * The rows for every station are already in `allStats` and already
         * grouped, but this loop used to call generateReport(deviceUid, ...),
         * which re-queried the repository once per station -- 1 + N round trips
         * for data already held in memory. The per-station rollup now works
         * from the group it already has.
         */
        List<EnvironmentalReportResponse> stationReports = byDevice.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> summarise(entry.getKey(), type, from, to, entry.getValue()))
                .toList();

        int totalReadings = sumReadings(allStats);
        int totalRain = allStats.stream()
                .mapToInt(d -> d.getRainEventsCount() != null ? d.getRainEventsCount() : 0).sum();

        /*
         * Two defects lived in the old fleet averages.
         *
         * First, they averaged per-station averages, which weights a station
         * that reported twice the same as one that reported ten thousand times.
         * A fleet mean is sum-of-values over count-of-readings, and both are
         * recorded on every row.
         *
         * Second, and worse: the sum filtered out null station averages but
         * still divided by the total station count. A fleet where three of ten
         * stations had no temperature divided seven values by ten, pulling the
         * reported fleet temperature roughly thirty percent toward zero.
         *
         * Air quality had the same shape plus `.orElse(0.0)`, so a fleet with
         * no air-quality data at all reported an average AQI of zero --
         * perfect air, asserted from no measurements.
         */
        return FleetReportResponse.builder()
                .reportType(type)
                .startDate(from)
                .endDate(to)
                .totalStations(byDevice.size())
                .totalReadings(totalReadings)
                .fleetAvgTemperature(weightedAverage(allStats, DailyStatistics::getSumTemperature, DailyStatistics::getTemperatureCount))
                .fleetAvgHumidity(weightedAverage(allStats, DailyStatistics::getSumHumidity, DailyStatistics::getHumidityCount))
                .fleetAvgAirQuality(weightedAirQualityAsDouble(allStats))
                .fleetAvgOxygen(weightedAverage(allStats, DailyStatistics::getSumOxygen, DailyStatistics::getOxygenCount))
                .fleetAvgPressure(weightedAverage(allStats, DailyStatistics::getSumPressure, DailyStatistics::getPressureCount))
                .totalRainEvents(totalRain)
                .stationReports(stationReports)
                .build();
    }

    /**
     * Sums an aggregate column across daily rows.
     *
     * <p>Not null-tolerant, deliberately. Since
     * V2__daily_statistics_aggregate_invariants these columns are NOT NULL with
     * a zero default, so a null reaching here means the invariant has been
     * broken somewhere upstream -- and that should be visible, not folded into
     * a total that reads like a measurement.
     */
    private BigDecimal sumColumn(List<DailyStatistics> stats, Function<DailyStatistics, BigDecimal> sumOf) {
        return stats.stream().map(sumOf).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Total readings behind a set of daily aggregate rows. */
    private int sumReadings(List<DailyStatistics> stats) {
        return stats.stream().mapToInt(d -> d.getReadingCount() != null ? d.getReadingCount() : 0).sum();
    }

    /**
     * Mean of a metric across daily aggregate rows, divided by the number of
     * readings that actually carried that metric.
     *
     * <p>This used to divide by the total reading count and decide "was
     * anything recorded?" by looking for a NULL sum. Neither worked. The
     * divisor counted readings that never contained the metric, so a station
     * without the sensor dragged the fleet average toward zero; and the NULL it
     * looked for could not occur, because DailyAggregateWriter writes the sums
     * through COALESCE(..., 0). A fleet with no air-quality data at all
     * therefore reported an average AQI of 0 -- perfect air, measured nowhere.
     *
     * <p>Null, never zero, in both of the cases where an average would be a
     * claim rather than a measurement: when no reading carried the metric, and
     * when the counts are unknown because the rows predate
     * V3__per_metric_reading_counts.
     */
    private BigDecimal weightedAverage(
            List<DailyStatistics> stats,
            Function<DailyStatistics, BigDecimal> sumOf,
            Function<DailyStatistics, Integer> countOf) {
        Integer measured = measuredReadings(stats, countOf);
        if (measured == null || measured <= 0) {
            return null;
        }
        BigDecimal total = BigDecimal.ZERO;
        for (DailyStatistics stat : stats) {
            BigDecimal value = sumOf.apply(stat);
            if (value != null) {
                total = total.add(value);
            }
        }
        return total.divide(BigDecimal.valueOf(measured), 2, RoundingMode.HALF_UP);
    }

    private Integer weightedAirQuality(List<DailyStatistics> stats) {
        Double average = weightedAirQualityAsDouble(stats);
        return average == null ? null : (int) Math.round(average);
    }

    private Double weightedAirQualityAsDouble(List<DailyStatistics> stats) {
        Integer measured = measuredReadings(stats, DailyStatistics::getAirQualityCount);
        if (measured == null || measured <= 0) {
            return null;
        }
        long total = 0;
        for (DailyStatistics stat : stats) {
            Long value = stat.getSumAirQuality();
            if (value != null) {
                total += value;
            }
        }
        return (double) total / measured;
    }

    /**
     * How many readings across these rows carried a given metric, or null when
     * that cannot be established.
     *
     * <p>A single row with an unknown count makes the total unknown: a partial
     * denominator would divide the full numerator and produce an average that
     * is simply wrong, which is worse than declining to answer.
     */
    private Integer measuredReadings(
            List<DailyStatistics> stats, Function<DailyStatistics, Integer> countOf) {
        int total = 0;
        for (DailyStatistics stat : stats) {
            Integer count = countOf.apply(stat);
            if (count == null) {
                return null;
            }
            total += count;
        }
        return total;
    }

    /** Half-height of the bounding box: one degree of latitude is ~111.32 km everywhere. */
    private double degreesOfLatitude(double radiusKm) {
        return radiusKm / KM_PER_DEGREE_LATITUDE;
    }

    /**
     * Half-width of the bounding box. Meridians converge toward the poles, so a
     * degree of longitude shrinks with the cosine of the latitude; near the
     * poles the box is clamped to the whole range rather than divided by zero.
     */
    private double degreesOfLongitude(double radiusKm, double atLatitude) {
        double shrink = Math.cos(Math.toRadians(atLatitude));
        if (shrink < 1e-6) {
            return 180.0;
        }
        return radiusKm / (KM_PER_DEGREE_LATITUDE * shrink);
    }

    private double calculateHaversineDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Radius of the earth in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    private BigDecimal divide(BigDecimal sum, int count) {
        if (sum == null || count == 0) return null;
        return sum.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP);
    }
}
