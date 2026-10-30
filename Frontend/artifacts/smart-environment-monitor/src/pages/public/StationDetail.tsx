import { Link, useParams } from 'react-router-dom';
import { ArrowRight, ChevronLeft, CloudRain, Droplets, Gauge, Leaf, Thermometer, Wind } from 'lucide-react';
import { PageIntro } from '@/components/PageHeaders';
import { Metric } from '@/components/Metric';
import { EnvironmentalChart } from '@/components/EnvironmentalChart';
import { Badge, toneForStationStatus } from '@/components/StatusIndicators';
import { ReadingAge } from '@/components/ReadingAge';
import { UnavailableNotice } from '@/components/DataState';
import { usePublicStation } from '@/hooks/use-public-data';
import { UNKNOWN, detection, reading } from '@/lib/format';

function BackLink() {
  return (
    <Link to="/stations" className="back-link" data-testid="link-back-stations">
      <ChevronLeft size={16} aria-hidden="true" /> Station directory
    </Link>
  );
}

export function StationDetail() {
  const { uid = '' } = useParams<{ uid: string }>();
  const stationQuery = usePublicStation(uid);

  if (stationQuery.isPending) {
    return <div className="page-container inner-page"><BackLink /><div className="empty-inline" data-testid="state-pending">Loading this station…</div></div>;
  }
  // A station that could not be fetched used to sit on "Loading…" forever.
  if (stationQuery.isError || !stationQuery.data) {
    return (
      <div className="page-container inner-page">
        <BackLink />
        <UnavailableNotice what={`The record for ${uid || 'this station'}`} error={stationQuery.error} onRetry={stationQuery.refetch} />
      </div>
    );
  }

  const station = stationQuery.data;
  const coordinates = station.position
    ? `${station.position.latitude.toFixed(4)}, ${station.position.longitude.toFixed(4)}`
    : UNKNOWN;

  return (
    <div className="page-container inner-page">
      <BackLink />
      <PageIntro
        eyebrow={`Station · ${station.uid}`}
        title={station.name}
        description={station.location}
        action={<Badge tone={toneForStationStatus(station.status)}>{station.status}</Badge>}
      />
      {station.reading === 'unavailable' && (
        <UnavailableNotice
          what="This station's latest reading"
          detail="The station is listed, but its most recent measurements were not returned."
          onRetry={stationQuery.refetch}
        />
      )}
      <div className="detail-layout">
        <div className="metric-grid six">
          <Metric icon={Thermometer} label="Temperature" value={reading(station.temperature, 1)} unit="°C" tone="amber" />
          <Metric icon={Droplets} label="Humidity" value={reading(station.humidity)} unit="%" tone="blue" />
          <Metric icon={Wind} label="Air quality" value={reading(station.airQuality)} unit="AQI" tone="teal" />
          <Metric icon={Leaf} label="Oxygen" value={reading(station.oxygen, 1)} unit="%" tone="blue" />
          <Metric icon={Gauge} label="Pressure" value={reading(station.pressure)} unit="hPa" tone="teal" />
          <Metric icon={CloudRain} label="Rain" value={detection(station.rain, 'Detected', 'None')} tone="blue" />
        </div>
        <aside className="panel station-status-card">
          <div className="eyebrow">Instrument record</div>
          <h2>About this station</h2>
          <dl className="fact-list">
            <div><dt>Station ID</dt><dd className="mono">{station.uid}</dd></div>
            <div><dt>Reading taken</dt><dd title={station.lastSeen}>
              {station.reading === 'ok' ? <ReadingAge readingAt={station.readingAt} /> : station.reading === 'absent' ? 'None received' : 'Could not be read'}
            </dd></div>
            <div><dt>Last report</dt><dd>{station.lastSeen}</dd></div>
            <div><dt>Coordinates</dt><dd className="mono">{coordinates}</dd></div>
          </dl>
          <Link to="/monitor" className="text-link">Open the live monitor <ArrowRight size={16} /></Link>
        </aside>
      </div>
      <div className="panel trend-panel">
        <div className="panel-heading">
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
