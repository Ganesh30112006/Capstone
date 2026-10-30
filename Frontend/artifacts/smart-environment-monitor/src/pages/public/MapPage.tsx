import { PageIntro } from '@/components/PageHeaders';
import { LeafletMap } from '@/components/LeafletMap';
import { StatusDot } from '@/components/StatusIndicators';
import { UnavailableNotice, derived } from '@/components/DataState';
import { usePublicStations } from '@/hooks/use-public-data';
import { readingWithUnit } from '@/lib/format';
import type { Station } from '@/types/domain';

export function MapPage() {
  const stationsQuery = usePublicStations();
  const stations: Station[] = stationsQuery.data ?? [];

  return (
    <div className="page-container inner-page">
      <PageIntro
        eyebrow="Public map · field network"
        title="See the signal in place."
        description="Every station where it stands. Select a marker for its latest reading."
      />
      {/* Without this, an unreachable backend draws a blank map of a region
          that looks as though it simply has no instruments in it. */}
      {stationsQuery.isError && (
        <UnavailableNotice what="The station network" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}
      <LeafletMap stations={stations} heightClassName="map-height-standard" valueFor={(s) => readingWithUnit(s.temperature, '°', 1)} />
      <div className="map-legend">
        <span><StatusDot status="online" />Online {derived(stationsQuery, (s) => s.filter((x) => x.status === 'online').length)}</span>
        <span><StatusDot status="warning" />Needs attention {derived(stationsQuery, (s) => s.filter((x) => x.status === 'warning').length)}</span>
        <span><StatusDot status="offline" />Offline {derived(stationsQuery, (s) => s.filter((x) => x.status === 'offline').length)}</span>
      </div>
    </div>
  );
}
