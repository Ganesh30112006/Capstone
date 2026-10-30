import { signalQuality } from '@/lib/format';
import { Link, useParams } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { ChevronRight, MapPinned, RefreshCw, Settings } from 'lucide-react';
import { deviceApi } from '@/api/deviceApi';
import { sensorApi } from '@/api/sensorApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { EnvironmentalChart } from '@/components/EnvironmentalChart';
import { SensorRow } from '@/components/SensorRow';
import { Badge, toneForStationStatus } from '@/components/StatusIndicators';
import { DataState, UnavailableNotice } from '@/components/DataState';

export function DeviceDetail() {
  const { deviceUid = '' } = useParams<{ deviceUid: string }>();
  const stationQuery = useQuery({ queryKey: ['station', deviceUid], queryFn: () => deviceApi.get(deviceUid) });
  const inventoryQuery = useQuery({ queryKey: ['sensors'], queryFn: sensorApi.getSensors });

  // A bare `if (!station) return <div>Loading…</div>` made a device record that
  // failed to load indistinguishable from one still loading — the page simply
  // said "Loading…" forever.
  if (stationQuery.isPending) {
    return <div className="empty-inline" style={{ padding: '2rem' }}>Loading the device record…</div>;
  }
  if (stationQuery.isError || !stationQuery.data) {
    return (
      <>
        <Link to="/admin/devices" className="back-link admin-back" data-testid="link-back-devices"><ChevronRight size={14} className="rotate-180" /> Devices</Link>
        <UnavailableNotice
          what={`The record for ${deviceUid || 'this station'}`}
          error={stationQuery.error}
          onRetry={stationQuery.refetch}
        />
      </>
    );
  }

  const station = stationQuery.data;
  const unreadable = inventoryQuery.data?.unreadable.includes(station.uid) ?? false;

  return (
    <>
      <Link to="/admin/devices" className="back-link admin-back" data-testid="link-back-devices"><ChevronRight size={14} className="rotate-180" /> Devices</Link>
      <AdminPageHeader
        eyebrow={`DEVICE RECORD · ${station.uid}`}
        title={station.name}
        description={`${station.location} · Last seen ${station.lastSeen}`}
        /*
          Both of these did nothing when clicked. A silently inert "Reboot
          station" is the most dangerous control in the console: an operator
          can believe they restarted an instrument that never received the
          command. The working path is Hardware debug, which dispatches REBOOT
          through device-service behind a confirmation, so this points there
          instead of pretending to act.
        */
        actions={<>
          <Link to="/admin/hardware-debug" className="button button-secondary" data-testid="link-reboot-device"><RefreshCw size={14} />Reboot via hardware debug</Link>
          <button className="button button-primary" disabled title="Editing a device record is not wired to anything yet." data-testid="button-edit-device"><Settings size={14} />Edit device</button>
        </>}
      />
      {station.reading === 'unavailable' && (
        <UnavailableNotice
          what="This station's latest reading"
          detail="The device record loaded, but sensor-service did not return its most recent measurements."
          onRetry={stationQuery.refetch}
        />
      )}
      <div className="device-detail-grid">
        <div className="panel device-summary">
          <div className="device-identity">
            <div className="device-glyph"><MapPinned size={24} /></div>
            <div>
              <Badge tone={toneForStationStatus(station.status)}>{station.status}</Badge>
              <h3>{station.uid}</h3>
            </div>
          </div>
          <div className="device-facts">
            <span><small>Firmware</small><strong className="mono">{station.firmware ?? 'Not reported'}</strong></span>
            <span><small>Wi-Fi signal</small><strong>{station.wifiSignal === null ? 'Not reported' : signalQuality(station.wifiSignal)}</strong></span>
            <span><small>Last reading</small><strong>{station.reading === 'ok' ? station.lastSeen : station.reading === 'absent' ? 'None received' : 'Could not be read'}</strong></span>
            <span><small>Battery</small><strong className={station.battery !== null && station.battery < 40 ? 'warn-text' : ''}>{station.battery === null ? 'Not reported' : `${station.battery}%`}</strong></span>
          </div>
        </div>
        <div className="panel">
          <div className="panel-heading">
            <div><div className="eyebrow">ATTACHED SENSORS</div><h3>Sensor health</h3></div>
            <Link to="/admin/sensors" className="text-link">View all <ChevronRight size={13} /></Link>
          </div>
          <DataState
            query={inventoryQuery}
            what="sensor diagnostics"
            isEmpty={(i) => i.sensors.filter((s) => s.stationUid === station.uid).length === 0}
            empty={
              unreadable
                ? <div className="empty-inline">This station did not answer its health probe, so its sensors are unknown — not absent.</div>
                : <div className="empty-inline">No sensor telemetry available.</div>
            }
          >
            {(inventory) => inventory.sensors
              .filter((sensor) => sensor.stationUid === station.uid)
              .map((sensor) => <SensorRow sensor={sensor} key={sensor.name} />)}
          </DataState>
        </div>
        <div className="panel detail-chart">
          <div className="panel-heading">
            <div><div className="eyebrow">DEVICE TELEMETRY</div><h3>Recent readings</h3></div>
            <span className="panel-meta">24 hours</span>
          </div>
          <EnvironmentalChart compact />
        </div>
      </div>
    </>
  );
}
