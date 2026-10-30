import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Cpu, Filter, Search } from 'lucide-react';
import { deviceApi } from '@/api/deviceApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { StationTable } from '@/components/StationTable';
import { DataState } from '@/components/DataState';
import { DeviceActivityFeed } from '@/components/DeviceActivityFeed';
import { useLiveStream } from '@/hooks/use-live-stream';
import { UNKNOWN } from '@/lib/format';

export function Devices() {
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState('all');
  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const live = useLiveStream(true);
  const allStations = stationsQuery.data ?? [];
  const matches = (uid: string, name: string, location: string) =>
    `${name} ${uid} ${location}`.toLowerCase().includes(query.toLowerCase());
  const stations = allStations.filter((s) => (filter === 'all' || s.status === filter) && matches(s.uid, s.name, s.location));

  return (
    <>
      <AdminPageHeader
        // A device count is a claim about the fleet, so it is only stated once
        // the fleet has actually been listed.
        eyebrow={`FLEET · ${stationsQuery.data ? String(allStations.length).padStart(2, '0') : UNKNOWN} DEVICES`}
        title="The fleet, at a glance."
        description="Find a station, inspect its health, or open a device record."
        actions={<button className="button button-primary" disabled title="This control is not wired to anything yet." data-testid="button-add-device"><Cpu size={14} />Register device</button>}
      />
      <div className="panel fleet-panel">
        <div className="toolbar admin-toolbar">
          <div className="input-wrap"><Search size={15} /><input value={query} onChange={(e) => setQuery(e.target.value)} placeholder="Search station, UID, or location" data-testid="input-device-search" /></div>
          <div className="segmented">
            {['all', 'online', 'warning', 'offline'].map((item) => (
              <button className={filter === item ? 'active' : ''} key={item} onClick={() => setFilter(item)} data-testid={`button-device-filter-${item}`}>{item}</button>
            ))}
          </div>
          <button className="button button-secondary filter-button" disabled title="This control is not wired to anything yet." data-testid="button-device-filters"><Filter size={14} />More filters</button>
        </div>
        <DataState
          query={stationsQuery}
          what="the fleet"
          empty={
            <div className="empty-state compact-empty">
              <Cpu size={21} /><h3>No devices registered</h3>
              <p>Register a station before it can report readings to the platform.</p>
            </div>
          }
        >
          {() => (
            <>
              <StationTable stations={stations} />
              {!stations.length && (
                // The fleet loaded; this filter simply matches none of it.
                <div className="empty-state compact-empty"><Search size={21} /><h3>No devices match</h3><p>Clear your search or filter to see the full fleet.</p></div>
              )}
            </>
          )}
        </DataState>
      </div>
      <DeviceActivityFeed events={live.deviceEvents} status={live.status} />
    </>
  );
}
