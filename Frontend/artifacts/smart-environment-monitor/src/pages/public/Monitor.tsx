import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ArrowRight, CloudRain, Droplets, Gauge, Leaf, Thermometer, Wind } from 'lucide-react';
import { StationPicker } from '@/components/StationPicker';
import { PageIntro } from '@/components/PageHeaders';
import { Metric } from '@/components/Metric';
import { EnvironmentalChart } from '@/components/EnvironmentalChart';
import { Badge, toneForStationStatus } from '@/components/StatusIndicators';
import { ReadingAge } from '@/components/ReadingAge';
import { UnavailableNotice } from '@/components/DataState';
import { PUBLIC_REFRESH_MS } from '@/api/publicApi';
import { usePublicStations } from '@/hooks/use-public-data';
import { detection, reading } from '@/lib/format';
import type { Station } from '@/types/domain';

const INTRO = { eyebrow: 'Live monitor', title: 'Right now.', description: 'The latest reading from the selected station.' };

export function Monitor() {
  const stationsQuery = usePublicStations();
  const stations: Station[] = stationsQuery.data ?? [];
  const [selectedUid, setSelectedUid] = useState('');
  const station = stations.find((s) => s.uid === selectedUid) ?? stations[0];

  if (stationsQuery.isPending) {
    return <div className="page-container inner-page"><PageIntro {...INTRO} /><div className="empty-inline" data-testid="state-pending">Loading the station network…</div></div>;
  }
  if (stationsQuery.isError) {
    return (
      <div className="page-container inner-page">
        <PageIntro {...INTRO} />
        <UnavailableNotice what="The station network" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      </div>
    );
  }
  if (!station) {
    return (
      <div className="page-container inner-page">
        <PageIntro {...INTRO} />
        <div className="empty-state"><h3>No stations are registered</h3><p>There is nothing to monitor on this network yet.</p></div>
      </div>
    );
  }

  /*
    Each tile's footnote says the one thing known about its value: whether it
    is a current reading at all. They once read "Within seasonal range" and
    "Good · low particulate" -- fixed strings, true of no particular number.
  */
  const provenance =
    station.reading === 'ok' ? 'Reported by the station'
      : station.reading === 'absent' ? 'No reading received yet'
        : 'Reading could not be fetched';

  return (
    <div className="page-container inner-page">
      <PageIntro
        eyebrow={`Live monitor · ${station.uid}`}
        title={`${station.name}, right now.`}
        description="The latest values this station reported, re-read every few seconds."
        action={<StationPicker stations={stations} value={station.uid} onChange={setSelectedUid} testId="select-monitor-station" label="Station to monitor" />}
      />
      {station.reading === 'unavailable' && (
        <UnavailableNotice
          what="This station's latest reading"
          detail="The station is listed, but its most recent measurements were not returned."
          onRetry={stationsQuery.refetch}
        />
      )}
      <div className="monitor-layout">
        <div className="metric-grid six">
          <Metric icon={Thermometer} label="Temperature" value={reading(station.temperature, 1)} unit="°C" tone="amber" foot={provenance} />
          <Metric icon={Droplets} label="Humidity" value={reading(station.humidity)} unit="%" tone="blue" foot={provenance} />
          <Metric icon={Wind} label="Air quality" value={reading(station.airQuality)} unit="AQI" tone="teal" foot={provenance} />
          <Metric icon={Leaf} label="Oxygen" value={reading(station.oxygen, 1)} unit="%" tone="blue" foot={provenance} />
          <Metric icon={Gauge} label="Pressure" value={reading(station.pressure)} unit="hPa" tone="teal" foot={provenance} />
          {/* Rain is a yes/no detection; "not detected" stays distinct from "not known". */}
          <Metric icon={CloudRain} label="Rain" value={detection(station.rain, 'Detected', 'None')} tone="blue" foot={provenance} />
        </div>
        <aside className="panel station-status-card">
          <div className="eyebrow">Station</div>
          <h2>{station.name}</h2>
          <dl className="fact-list">
            <div><dt>Status</dt><dd><Badge tone={toneForStationStatus(station.status)}>{station.status}</Badge></dd></div>
            <div><dt>Reading taken</dt><dd title={station.lastSeen}><ReadingAge readingAt={station.readingAt} /></dd></div>
            <div><dt>Location</dt><dd>{station.location}</dd></div>
            <div><dt>Refreshes</dt><dd>every {Math.round(PUBLIC_REFRESH_MS / 1000)} s</dd></div>
          </dl>
          <Link to={`/stations/${station.uid}`} className="text-link" data-testid="link-monitor-station">Station details <ArrowRight size={16} /></Link>
        </aside>
      </div>
      <div className="panel trend-panel">
        <div className="panel-heading">
          {/* Daily averages across every station -- not this station's history. */}
          <div><div className="eyebrow">Network trend · all stations</div><h2>Daily averages</h2></div>
          <span className="panel-meta">
            <span className="legend-line" />Temperature<span className="legend-line blue" />Humidity<span className="legend-line violet" />Air quality
          </span>
        </div>
        <EnvironmentalChart source="public" />
      </div>
    </div>
  );
}
