import { useQuery } from '@tanstack/react-query';
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip as ChartTooltip, XAxis, YAxis } from 'recharts';
import { analyticsApi } from '@/api/analyticsApi';
import { publicApi } from '@/api/publicApi';

export type TrendSource = 'operator' | 'public';

/**
 * Network-wide daily averages. Not any one station's history: pages that place
 * it beside a station say so in its heading.
 *
 * The public site reads the anonymous trend; the console reads the operator
 * one. Colours come from the theme (src/styles/components.css), so the same
 * chart is right on both.
 */
export default function EnvironmentalChartView({ compact = false, source = 'operator' }: { compact?: boolean; source?: TrendSource }) {
  const trendQuery = useQuery({
    queryKey: source === 'public' ? ['public', 'trend'] : ['trends'],
    queryFn: source === 'public' ? publicApi.getTrend : analyticsApi.getNetworkTrend,
    staleTime: 5 * 60_000,
  });
  const wrapClass = `chart-wrap ${compact ? 'compact' : ''}`;

  /*
    A chart defaulting to an empty array draws its grid and axes and nothing
    else, which looks like a measured flatline rather than a missing series.
    This component is embedded unguarded on the dashboard, the monitor, the
    station pages and the device record, so it has to state its own condition
    instead of relying on each host page to wrap it.
  */
  if (trendQuery.isPending) {
    return <div className={wrapClass} aria-busy="true" data-testid="chart-pending" />;
  }
  if (trendQuery.isError) {
    return (
      <div className={`${wrapClass} chart-message`} role="status" data-testid="chart-unavailable">
        The trend series could not be loaded, so nothing is plotted here — this is not a flat signal.
      </div>
    );
  }
  if (!trendQuery.data.length) {
    return (
      <div className={`${wrapClass} chart-message`} data-testid="chart-empty">
        No daily statistics have been recorded yet.
      </div>
    );
  }

  return (
    <div className={wrapClass} data-testid="chart-environmental-trends">
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={trendQuery.data} margin={{ top: 12, right: 10, bottom: 0, left: -22 }}>
          <CartesianGrid vertical={false} />
          <XAxis dataKey="timestamp" tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
          <YAxis tick={{ fontSize: 12 }} axisLine={false} tickLine={false} />
          <ChartTooltip />
          <Line type="monotone" dataKey="temperature" className="chart-series series-temperature" stroke="currentColor" connectNulls={false} dot={false} name="Temperature °C" />
          <Line type="monotone" dataKey="humidity" className="chart-series series-humidity" stroke="currentColor" connectNulls={false} dot={false} name="Humidity %" />
          {!compact && <Line type="monotone" dataKey="airQuality" className="chart-series series-air-quality" stroke="currentColor" connectNulls={false} dot={false} name="Air quality AQI" />}
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}
