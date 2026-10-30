import { useEffect, useMemo, useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, ChevronDown, LocateFixed } from 'lucide-react';
import { DataState, type QueryLike } from '@/components/DataState';
import { ReadingAge } from '@/components/ReadingAge';
import { StatusDot } from '@/components/StatusIndicators';
import { TempGauge } from '@/components/TempGauge';
import { PUBLIC_REFRESH_MS } from '@/api/publicApi';
import { usePublicForecastSkill, usePublicStations } from '@/hooks/use-public-data';
import { UNKNOWN, detection, reading, readingWithUnit } from '@/lib/format';
import { distanceMetres, formatDistance } from '@/lib/geo';
import { airQuality, feelsLike, humidity, rain, type Interpretation } from '@/lib/interpret';
import type { Station } from '@/types/domain';

/*
 * The observatory's front page: one station's conditions, what they mean in
 * plain words, and the rest of the network. Nothing here is a literal standing
 * in for a measurement -- an unknown value is the em dash, and the guidance
 * beside it disappears with it.
 */

type Located = { station: Station; metres: number };

function nearestTo(coords: [number, number], stations: Station[]): Located | null {
  let best: Located | null = null;
  for (const station of stations) {
    if (!station.position) continue;
    const metres = distanceMetres(coords, [station.position.latitude, station.position.longitude]);
    if (!best || metres < best.metres) best = { station, metres };
  }
  return best;
}

function NetworkStatus({ stations, isError, isPending }: { stations: Station[]; isError: boolean; isPending: boolean }) {
  if (isError) return <p className="home-status" data-state="error" role="status">The station network could not be reached from this browser.</p>;
  if (isPending) return <p className="home-status" data-state="pending">Checking the network…</p>;
  const reporting = stations.filter((s) => s.status === 'online').length;
  return (
    <p className="home-status" data-state="ok">
      <span className="home-status-dot" aria-hidden="true" />
      {reporting} of {stations.length} stations reporting
    </p>
  );
}

function Stat({ label, value, meaning }: { label: string; value: string; meaning: Interpretation | null }) {
  return (
    <div className="home-stat">
      <span>{label}</span>
      <strong data-numeric>{value}</strong>
      <small>{meaning?.label ?? 'Not known'}</small>
    </div>
  );
}

function ConditionsCard({ station, located, isError, isPending }: {
  station: Station | null; located: Located | null; isError: boolean; isPending: boolean;
}) {
  const feel = station ? feelsLike(station.temperature, station.humidity) : null;
  return (
    <article className="home-card conditions" aria-label={station ? `Current conditions at ${station.name}` : 'Current conditions'}>
      {!station ? (
        <div className="conditions-body">
          <p className="conditions-empty">
            {isError ? 'The network could not be reached.' : isPending ? 'Loading the network…' : 'No station has reported yet.'}
          </p>
        </div>
      ) : (
        <div className="conditions-body">
          <div className="conditions-head">
            <div>
              <h2>{station.name}</h2>
              <p>
                {station.location}
                {located?.station.uid === station.uid && <> · about {formatDistance(located.metres)} from you</>}
              </p>
            </div>
            <span className="conditions-age"><ReadingAge readingAt={station.readingAt} /></span>
          </div>
          <TempGauge celsius={station.temperature} feelsLike={feel?.adjusted ? feel.celsius : null} />
          <p className="conditions-feel">
            {feel ? (feel.adjusted ? `Feels like ${Math.round(feel.celsius)}°C. ${feel.advice}` : feel.advice) : UNKNOWN}
          </p>
          <div className="conditions-stats">
            <Stat label="Humidity" value={readingWithUnit(station.humidity, '%')} meaning={humidity(station.humidity)} />
            <Stat label="Air index" value={reading(station.airQuality)} meaning={airQuality(station.airQuality)} />
            <Stat label="Rain" value={detection(station.rain, 'Yes', 'No')} meaning={rain(station.rain)} />
          </div>
          <Link to={`/stations/${station.uid}`} className="home-link" data-testid="link-featured-station">
            Station details <ArrowRight size={16} aria-hidden="true" />
          </Link>
        </div>
      )}
    </article>
  );
}

function Meaning({ station }: { station: Station | null }) {
  const feel = station ? feelsLike(station.temperature, station.humidity) : null;
  const air = station ? airQuality(station.airQuality) : null;
  const damp = station ? humidity(station.humidity) : null;
  const items: Array<{ key: string; title: string; meaning: Interpretation | null; fallback: string }> = [
    { key: 'feel', title: feel?.adjusted ? `Feels like ${Math.round(feel.celsius)}°C` : feel ? `${feel.label} outside` : 'Temperature', meaning: feel, fallback: 'No temperature reading, so no advice.' },
    { key: 'air', title: air ? `Air quality: ${air.label}` : 'Air quality', meaning: air, fallback: 'No air reading, so no advice.' },
    { key: 'humidity', title: damp ? `Humidity: ${damp.label}` : 'Humidity', meaning: damp, fallback: 'No humidity reading, so no advice.' },
  ];
  return (
    <section className="home-section" aria-labelledby="meaning-heading">
      <div className="home-section-head">
        <h2 id="meaning-heading">What it means for you</h2>
      </div>
      <div className="meaning-grid">
        {items.map((item) => (
          <div className={`home-card meaning-card tone-${item.meaning?.tone ?? 'unknown'}`} key={item.key}>
            <span className="meaning-dot" aria-hidden="true" />
            <h3>{item.title}</h3>
            <p>{item.meaning?.advice ?? item.fallback}</p>
          </div>
        ))}
      </div>
    </section>
  );
}

function Stations({ query }: { query: QueryLike<Station[]> }) {
  return (
    <section className="home-section" aria-labelledby="stations-heading">
      <div className="home-section-head">
        <h2 id="stations-heading">Stations on the network</h2>
        <Link to="/stations" className="home-link" data-testid="link-all-stations">View all <ArrowRight size={16} aria-hidden="true" /></Link>
      </div>
      {/* "Could not ask" and "asked, and there are none" stay two different screens. */}
      <DataState
        query={query}
        what="the station network"
        empty={<div className="empty-inline">No stations are reporting to this network yet.</div>}
      >
        {(stations) => (
          <div className="station-list">
            {stations.map((s) => {
              const air = airQuality(s.airQuality);
              return (
                <Link to={`/stations/${s.uid}`} className="home-card station-item" key={s.uid} data-testid={`card-station-${s.uid}`}>
                  <div className="station-item-top">
                    <span className="station-item-status">
                      <StatusDot status={s.status} decorative />
                      {s.status === 'online' ? 'Reporting' : s.status === 'warning' ? 'Warning' : 'Offline'}
                    </span>
                    <ReadingAge readingAt={s.readingAt} />
                  </div>
                  <h3>{s.location}</h3>
                  <p>{s.name}</p>
                  <TempGauge celsius={s.temperature} size="small" />
                  <p className="station-item-air">Air {air ? air.label.toLowerCase() : UNKNOWN} · humidity {readingWithUnit(s.humidity, '%')}</p>
                </Link>
              );
            })}
          </div>
        )}
      </DataState>
    </section>
  );
}

/** One line from the AI's public scorecard, linking to the full check. */
function AiTeaser() {
  const skill = usePublicForecastSkill();
  const t = skill.data?.methods['chronos-2']?.metrics.temperature;
  const line = skill.isError ? 'The forecast scores could not be loaded right now.'
    : !skill.data ? 'Loading the scores…'
      : t?.enough && typeof t.winRate === 'number'
        ? `Its temperature forecast was closer than a simple guess ${Math.round(t.winRate * 100)}% of the time, across ${t.scored.toLocaleString()} checks.`
        : `${(skill.data.totalScored ?? 0).toLocaleString()} forecasts checked so far. A score appears once there are enough.`;
  return (
    <section className="home-section" aria-labelledby="ai-teaser-heading">
      <Link to="/ai-check" className="home-card ai-teaser" data-testid="link-ai-check">
        <div>
          <strong id="ai-teaser-heading">Is the AI any good? We check every forecast.</strong>
          <p>{line}</p>
        </div>
        <ArrowRight size={20} aria-hidden="true" />
      </Link>
    </section>
  );
}

const QUESTIONS: Array<[string, string]> = [
  ['Why do some values show a dash?', 'A dash means the value is not known: the station has not sent it, or its latest reading could not be fetched. We never fill a gap with a zero or a guess.'],
  ['Is the air number an official AQI?', 'No. It is each station’s own index from a low-cost sensor, on the same 0–500 scale as the US AQI. Read the bands as a local guide, not a government report.'],
  ['What does “feels like” mean?', 'It is the heat index used by the US National Weather Service: how hot humid air feels on the skin. It applies from about 27 °C with 40% humidity or more; otherwise you see the plain temperature.'],
  ['Have the sensors been checked?', 'Where a sensor has been compared with a reference instrument, its correction is applied before you see the value. The system is well tested and calibrated, but not yet field-validated against an official station.'],
  ['Where does my location go?', 'If you share it, it is used inside your browser to find the nearest station and is not sent anywhere. No account is needed.'],
];

function Questions() {
  return (
    <section className="home-section" aria-labelledby="faq-heading" id="reading-the-numbers">
      <div className="home-section-head">
        <h2 id="faq-heading">Common questions</h2>
      </div>
      <div className="faq-list">
        {QUESTIONS.map(([q, a]) => (
          <details className="home-card faq-item" key={q}>
            <summary>{q}<ChevronDown size={18} aria-hidden="true" /></summary>
            <p>{a}</p>
          </details>
        ))}
      </div>
    </section>
  );
}

export function Home() {
  const stationsQuery = usePublicStations();
  const stations = useMemo(() => stationsQuery.data ?? [], [stationsQuery.data]);
  const [pickedUid, setPickedUid] = useState<string | null>(null);
  const [coords, setCoords] = useState<[number, number] | null>(null);
  const [locating, setLocating] = useState(false);
  const [locationNote, setLocationNote] = useState<string | null>(null);

  const located = useMemo(() => (coords ? nearestTo(coords, stations) : null), [coords, stations]);
  const featured: Station | null = stations.find((s) => s.uid === pickedUid)
    ?? located?.station
    ?? stations.find((s) => s.reading === 'ok')
    ?? stations[0]
    ?? null;

  const handleGeolocation = () => {
    if (!('geolocation' in navigator)) {
      setLocationNote('This browser cannot share a location.');
      return;
    }
    setLocating(true);
    setLocationNote(null);
    navigator.geolocation.getCurrentPosition(
      (position) => {
        setLocating(false);
        setPickedUid(null);
        setCoords([position.coords.latitude, position.coords.longitude]);
      },
      (error) => {
        setLocating(false);
        setLocationNote(error.code === error.PERMISSION_DENIED
          ? 'Location was declined, so pick a town instead.'
          : 'Your location could not be found right now.');
      },
      { timeout: 10_000, maximumAge: 300_000 },
    );
  };

  useEffect(() => {
    if (coords && stationsQuery.isSuccess && !located) {
      setLocationNote('No station has a published position to measure from.');
    }
  }, [coords, located, stationsQuery.isSuccess]);

  return (
    <div className="home">
      <section className="home-hero">
        <div className="home-hero-copy">
          <NetworkStatus stations={stations} isError={stationsQuery.isError} isPending={stationsQuery.isPending} />
          <h1>Live air and weather across Andhra Pradesh.</h1>
          <p className="home-lead">
            Readings from local field stations, refreshed every {Math.round(PUBLIC_REFRESH_MS / 1000)} seconds, with what they mean for your day in plain words.
          </p>
          <div className="home-controls">
            <button type="button" className="home-button" onClick={handleGeolocation} disabled={locating} data-testid="button-geolocation">
              <LocateFixed size={18} aria-hidden="true" /> {locating ? 'Locating…' : 'Use my location'}
            </button>
            <label className="home-select">
              <span className="visually-hidden">Choose a town</span>
              <select
                value={featured?.uid ?? ''}
                onChange={(e) => setPickedUid(e.target.value)}
                disabled={!stations.length}
                data-testid="select-board-station"
              >
                {!stations.length && <option value="">No stations yet</option>}
                {stations.map((s) => <option key={s.uid} value={s.uid}>{s.location} · {s.name}</option>)}
              </select>
            </label>
          </div>
          {locationNote && <p className="home-note" role="status">{locationNote}</p>}
        </div>
        <ConditionsCard station={featured} located={located} isError={stationsQuery.isError} isPending={stationsQuery.isPending} />
      </section>

      <Meaning station={featured} />
      <AiTeaser />
      <Stations query={stationsQuery} />
      <Questions />
    </div>
  );
}
