import { useNavigate } from 'react-router-dom';
import { useQuery } from '@tanstack/react-query';
import { deviceApi } from '@/api/deviceApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { LeafletMap } from '@/components/LeafletMap';
import { StatusDot } from '@/components/StatusIndicators';
import { UnavailableNotice, derived } from '@/components/DataState';
import { readingWithUnit } from '@/lib/format';
import type { Station } from '@/types/domain';

export function AdminMap() {
  const navigate = useNavigate();
  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const stations: Station[] = stationsQuery.data ?? [];

  return (
    <>
      <AdminPageHeader
        eyebrow="SPATIAL OPERATIONS · LIVE MAP"
        title="Where the network is working."
        description="Operational map view for station location, connectivity, and field context. Select a marker to open its device record."
      />
      {/*
        An empty map is the most convincing lie this console can tell: it looks
        like a network with no stations rather than a console with no answer.
      */}
      {stationsQuery.isError && (
        <UnavailableNotice what="The fleet" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}
      <LeafletMap
        stations={stations}
        heightClassName="map-height-admin"
        valueFor={(s) => readingWithUnit(s.temperature, '°', 1)}
        onSelectStation={(uid) => navigate(`/admin/devices/${uid}`)}
        showControls
      />
      <div className="map-legend">
        <span><StatusDot status="online" />Online {derived(stationsQuery, (s) => s.filter((x) => x.status === 'online').length)}</span>
        <span><StatusDot status="warning" />Warning {derived(stationsQuery, (s) => s.filter((x) => x.status === 'warning').length)}</span>
        <span><StatusDot status="offline" />Offline {derived(stationsQuery, (s) => s.filter((x) => x.status === 'offline').length)}</span>
      </div>
    </>
  );
}
