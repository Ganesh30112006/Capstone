import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ChevronRight, Clock3, MapPinned, Search } from 'lucide-react';
import { PageIntro } from '@/components/PageHeaders';
import { Badge, toneForStationStatus } from '@/components/StatusIndicators';
import { DataState } from '@/components/DataState';
import { ReadingAge } from '@/components/ReadingAge';
import { usePublicStations } from '@/hooks/use-public-data';
import { UNKNOWN, reading, readingWithUnit } from '@/lib/format';

const FILTERS = ['all', 'online', 'warning', 'offline'] as const;

export function StationDirectory() {
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState<(typeof FILTERS)[number]>('all');
  const stationsQuery = usePublicStations();
  const allStations = stationsQuery.data ?? [];
  const visible = allStations.filter(
    (s) => (status === 'all' || s.status === status)
      && `${s.name} ${s.location} ${s.uid}`.toLowerCase().includes(query.trim().toLowerCase()),
  );

  return (
    <div className="page-container inner-page">
      <PageIntro
        // A station count is a claim; it is made only once the list has loaded.
        eyebrow={`Public directory · ${stationsQuery.data ? allStations.length : UNKNOWN} stations`}
        title="The stations in the field."
        description="Every instrument reporting conditions across the region, with the reading it sent most recently."
      />
      <div className="toolbar">
        <label className="input-wrap">
          <Search size={18} aria-hidden="true" />
          <span className="visually-hidden">Filter stations</span>
          <input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Filter by name, place or ID" data-testid="input-station-filter" />
        </label>
        <div className="segmented" role="group" aria-label="Filter by status">
          {FILTERS.map((item) => (
            <button
              key={item}
              className={status === item ? 'active' : ''}
              aria-pressed={status === item}
              onClick={() => setStatus(item)}
              data-testid={`button-station-status-${item}`}
            >
              {item}
            </button>
          ))}
        </div>
      </div>

      <DataState
        query={stationsQuery}
        what="the station directory"
        empty={
          <div className="empty-state">
            <div><MapPinned size={28} aria-hidden="true" /><h3>No stations registered</h3>
              <p>No instruments have been registered on this network yet.</p></div>
          </div>
        }
      >
        {() => (
          <>
            <div className="directory-grid">
              {visible.map((station) => (
                <Link to={`/stations/${station.uid}`} className="station-card" key={station.uid} data-testid={`card-directory-${station.uid}`}>
                  <div className="station-card-top">
                    <Badge tone={toneForStationStatus(station.status)}>{station.status}</Badge>
                    <span className="mono">{station.uid}</span>
                  </div>
                  <div>
                    <h3>{station.name}</h3>
                    <p><MapPinned size={15} aria-hidden="true" />{station.location}</p>
                  </div>
                  {/* Unknown values are a dash; `{station.battery}%` once printed "null%". */}
                  <div className="station-card-readings">
                    <span><small>Temp</small>{readingWithUnit(station.temperature, '°', 1)}</span>
                    <span><small>Humidity</small>{readingWithUnit(station.humidity, '%')}</span>
                    <span><small>AQI</small>{reading(station.airQuality)}</span>
                  </div>
                  <div className="station-card-foot">
                    <span title={station.lastSeen}><Clock3 size={13} aria-hidden="true" /> <ReadingAge readingAt={station.readingAt} /></span>
                    <ChevronRight size={18} aria-hidden="true" />
                  </div>
                </Link>
              ))}
            </div>
            {!visible.length && (
              <div className="empty-state">
                <div>
                  <Search size={28} aria-hidden="true" /><h3>No stations found</h3><p>Try a different place, name, or status filter.</p>
                  <button className="button button-secondary" onClick={() => { setQuery(''); setStatus('all'); }} data-testid="button-clear-station-filter">Clear filters</button>
                </div>
              </div>
            )}
          </>
        )}
      </DataState>
    </div>
  );
}
