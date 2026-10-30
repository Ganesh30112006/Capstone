import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowDownRight, ArrowRight, ArrowUpRight, CircleHelp, Minus } from 'lucide-react';
import { PageIntro } from '@/components/PageHeaders';
import { EnvironmentalChart } from '@/components/EnvironmentalChart';
import { StationPicker } from '@/components/StationPicker';
import { ReadingAge } from '@/components/ReadingAge';
import { UnavailableNotice } from '@/components/DataState';
import type { ForecastMetric, PublicForecast } from '@/api/publicApi';
import { usePublicForecast, usePublicStations } from '@/hooks/use-public-data';
import { UNKNOWN } from '@/lib/format';
import type { Station } from '@/types/domain';

const METRICS: Array<{ key: ForecastMetric; label: string; unit: string; digits: number; current: (s: Station) => number | null }> = [
  { key: 'temperature', label: 'Temperature', unit: '°C', digits: 1, current: (s) => s.temperature },
  { key: 'humidity', label: 'Humidity', unit: '%', digits: 1, current: (s) => s.humidity },
  { key: 'airQuality', label: 'Air quality', unit: 'AQI', digits: 0, current: (s) => s.airQuality },
  { key: 'pressure', label: 'Pressure', unit: 'hPa', digits: 1, current: (s) => s.pressure },
  { key: 'oxygen', label: 'Oxygen', unit: '%', digits: 2, current: (s) => s.oxygen },
];

function fixed(value: number | null | undefined, digits: number): string {
  return typeof value === 'number' && Number.isFinite(value) ? value.toFixed(digits) : UNKNOWN;
}

/** Direction from the current reading to the last forecast step. Flat inside half a display unit. */
function Trend({ from, to, digits }: { from: number | null; to: number | undefined; digits: number }) {
  if (from === null || to === undefined) return <span className="outlook-trend">{UNKNOWN}</span>;
  const delta = to - from;
  const flat = Math.abs(delta) < 0.5 * 10 ** -digits;
  const Icon = flat ? Minus : delta > 0 ? ArrowUpRight : ArrowDownRight;
  return (
    <span className="outlook-trend">
      <Icon size={15} aria-hidden="true" />
      {flat ? 'steady' : `${delta > 0 ? '+' : ''}${delta.toFixed(digits)}`}
    </span>
  );
}

/**
 * The model's expected next readings.
 *
 * This page used to label prediction #0 "Today" and #1 "Tomorrow" by their
 * position in the list, under a CloudSun icon, as if the model forecast the
 * weather days ahead. It forecasts the next few readings -- seconds ahead at
 * one reading a second -- to tell expected values from surprising ones. The
 * table says exactly that, and nothing more.
 */
function Outlook({ forecast, station }: { forecast: PublicForecast; station: Station }) {
  const steps = Array.from({ length: forecast.horizonReadings }, (_, i) => i + 1);
  const rows = METRICS.filter((m) => (forecast.predictions[m.key] ?? []).length > 0);
  return (
    <div className="panel outlook-panel" data-testid="forecast-outlook">
      <div className="panel-heading">
        <div>
          <div className="eyebrow">Short-range outlook · {station.name}</div>
          <h2>The next {forecast.horizonReadings} readings</h2>
        </div>
        <span className="panel-meta">
          {forecast.method ?? 'Forecast'}
          {forecast.historyPoints ? ` · from the last ${forecast.historyPoints} readings` : ''}
          {forecast.generatedAt && <> · made <ReadingAge readingAt={forecast.generatedAt} /></>}
        </span>
      </div>
      <div className="outlook-table-wrap">
        <table className="outlook-table">
          <thead>
            <tr>
              <th scope="col">Metric</th>
              <th scope="col">Now</th>
              {steps.map((n) => <th scope="col" key={n}>+{n}</th>)}
              <th scope="col">Change</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((metric) => {
              const values = forecast.predictions[metric.key] ?? [];
              const now = metric.current(station);
              return (
                <tr key={metric.key}>
                  <td>{metric.label} <span className="muted-cell">{metric.unit}</span></td>
                  <td className="now">{fixed(now, metric.digits)}</td>
                  {steps.map((n) => <td key={n}>{fixed(values[n - 1], metric.digits)}</td>)}
                  <td><Trend from={now} to={values[values.length - 1]} digits={metric.digits} /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export function Weather() {
  const stationsQuery = usePublicStations();
  const stations: Station[] = stationsQuery.data ?? [];
  const [selectedUid, setSelectedUid] = useState('');
  const station = stations.find((s) => s.uid === selectedUid) ?? stations[0];
  const forecastQuery = usePublicForecast(station?.uid ?? '');

  const forecastBody = !station ? null
    : forecastQuery.isPending ? <div className="empty-inline">Requesting the latest outlook for this station…</div>
      : forecastQuery.isError ? <UnavailableNotice what="The forecast" error={forecastQuery.error} onRetry={forecastQuery.refetch} />
        : !forecastQuery.data ? (
          <div className="callout" data-testid="forecast-empty">
            <CircleHelp size={18} aria-hidden="true" />
            <div>
              <strong>No outlook for this station yet</strong>
              <p>The model forecasts a station once it has enough recent readings. Nothing is shown until it has.</p>
            </div>
          </div>
        ) : <Outlook forecast={forecastQuery.data} station={station} />;

  return (
    <div className="page-container inner-page">
      <PageIntro
        eyebrow={`Forecast · ${station?.name ?? 'field network'}`}
        title="What the model expects next."
        description="An AI time-series model reads each station's recent history and predicts its next few readings."
        action={stations.length
          ? <StationPicker stations={stations} value={station?.uid ?? ''} onChange={setSelectedUid} testId="select-weather-station" label="Station to forecast" />
          : <span className="panel-meta">{stationsQuery.isSuccess ? 'No registered stations' : stationsQuery.isError ? 'Stations unavailable' : 'Loading stations…'}</span>}
      />

      {stationsQuery.isError && (
        <UnavailableNotice what="The station network" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}

      {forecastBody}

      <div className="panel trend-panel">
        <div className="panel-heading">
          <div><div className="eyebrow">Network trend · all stations</div><h2>Daily averages</h2></div>
          <span className="panel-meta">
            <span className="legend-line" />Temperature<span className="legend-line blue" />Humidity<span className="legend-line violet" />Air quality
          </span>
        </div>
        <EnvironmentalChart source="public" />
      </div>

      <div className="callout" style={{ marginTop: 'var(--space-4)' }}>
        <CircleHelp size={18} aria-hidden="true" />
        <div>
          <strong>What these numbers are</strong>
          <p>
            Point predictions for the next readings, not ranges and not a forecast for the days ahead. Their main
            job is to tell an expected reading from a surprising one.
          </p>
        </div>
        <Link to="/monitor" className="text-link">Open the live monitor <ArrowRight size={16} /></Link>
      </div>
    </div>
  );
}
