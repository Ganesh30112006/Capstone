import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Network } from 'lucide-react';
import { deviceApi } from '@/api/deviceApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge, StatusDot, toneForStationStatus } from '@/components/StatusIndicators';
import { useLiveStream } from '@/hooks/use-live-stream';
import { LiveStatusPulse } from '@/components/LiveStatusPulse';
import { UnavailableNotice } from '@/components/DataState';

export function DigitalTwin() {
  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const stations = stationsQuery.data ?? [];
  const [selectedUid, setSelectedUid] = useState('');
  const activeUid = selectedUid || stations[0]?.uid || '';
  const station = stations.find((s) => s.uid === activeUid);
  const live = useLiveStream(true);

  const twinQuery = useQuery({
    queryKey: ['digital-twin', activeUid],
    queryFn: () => deviceApi.getDigitalTwin(activeUid),
    enabled: !!activeUid,
  });
  const twin = twinQuery.data;

  const liveReading = live.telemetry[activeUid];
  const liveHeartbeat = live.heartbeats[activeUid];

  return (
    <>
      <AdminPageHeader
        eyebrow="OPERATIONS · DIGITAL TWIN"
        title="The station as the platform sees it."
        description="Reported state held by device-service, alongside whatever has arrived on the live stream since the page opened."
        actions={
          <select
            value={activeUid}
            onChange={(e) => setSelectedUid(e.target.value)}
            aria-label="Station to inspect"
            data-testid="select-twin-station"
            style={{ background: 'transparent', border: '1px solid #d6e0d9', borderRadius: 6, padding: '6px 10px', fontSize: 13 }}
          >
            {stations.length
              ? stations.map((s) => <option key={s.uid} value={s.uid}>{s.name} — {s.uid}</option>)
              : <option value="">{stationsQuery.isSuccess ? 'No registered stations' : 'Stations unavailable'}</option>}
          </select>
        }
      />

      {stationsQuery.isError && (
        <UnavailableNotice what="The station list" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}

      <div className="admin-generic-grid">
        <div className="panel generic-hero" style={{ height: 'auto', padding: '2rem' }}>
          <div className="generic-icon"><Network size={22} /></div>
          <div className="eyebrow">REPORTED STATE</div>
          <h2>{station?.name ?? activeUid ?? 'No station selected'}</h2>
          {station && (
            <div className="generic-stat-row" style={{ marginTop: '20px' }}>
              <span><small>Status</small><strong><Badge tone={toneForStationStatus(station.status)}>{station.status}</Badge></strong></span>
              <span><small>Firmware</small><strong className="mono">{station.firmware}</strong></span>
              <span><small>Last seen</small><strong>{station.lastSeen}</strong></span>
            </div>
          )}
          {twinQuery.isPending && activeUid && <p>Loading the twin record…</p>}
          {twinQuery.isError && <p data-testid="twin-unavailable">The twin record could not be fetched, so whether one is stored for this station is unknown.</p>}
          {twinQuery.isSuccess && !twin && activeUid && <p>No twin record has been stored for this station yet.</p>}
        </div>

        <div className="panel generic-list">
          <div className="panel-heading">
            <div><div className="eyebrow">LIVE STREAM</div><h3>Since this page opened</h3></div>
            <LiveStatusPulse status={live.status} />
          </div>
          {liveReading ? (
            <>
              <div className="simple-row">
                <span><strong>Last telemetry</strong><small>{liveReading.timestamp ? new Date(liveReading.timestamp).toLocaleTimeString() : 'just now'} · via {liveReading.source ?? 'stream'}</small></span>
              </div>
              {Object.entries(liveReading.values).map(([metric, value]) => (
                <div className="simple-row" key={metric}>
                  <span><strong>{metric}</strong></span>
                  <span className="mono">{String(value)}</span>
                </div>
              ))}
            </>
          ) : (
            <div className="empty-inline">No telemetry frames have arrived for this station yet.</div>
          )}
          {liveHeartbeat && (
            <div className="simple-row">
              <span><StatusDot status="online" /><strong>Heartbeat</strong><small>{liveHeartbeat.timestamp ? new Date(liveHeartbeat.timestamp).toLocaleTimeString() : 'recent'}</small></span>
            </div>
          )}
        </div>
      </div>
    </>
  );
}
