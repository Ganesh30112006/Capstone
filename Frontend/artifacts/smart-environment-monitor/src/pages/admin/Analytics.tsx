import { useQuery } from '@tanstack/react-query';
import { Download, Droplets, Gauge, Thermometer, Wind } from 'lucide-react';
import { analyticsApi } from '@/api/analyticsApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { EnvironmentalChart } from '@/components/EnvironmentalChart';
import { Metric } from '@/components/Metric';
import { DataState, UnavailableNotice, derived } from '@/components/DataState';
import { UNKNOWN } from '@/lib/format';
import type { TrendPoint } from '@/types/domain';

/** Days with no reading for the metric are left out, not counted as zero. */
function average(values: Array<number | null>): string {
  const known = values.filter((v): v is number => v !== null);
  if (!known.length) return UNKNOWN;
  return (known.reduce((sum, v) => sum + v, 0) / known.length).toFixed(1);
}

export function Analytics() {
  const trendQuery = useQuery({ queryKey: ['trends'], queryFn: analyticsApi.getNetworkTrend });
  const avg = (pick: (t: TrendPoint) => number | null) => derived(trendQuery, (t) => average(t.map(pick)));

  return (
    <>
      <AdminPageHeader
        eyebrow="HISTORICAL TRENDS · ANALYTICS"
        title="What the network has been doing."
        description="Fleet-wide daily averages across every reporting station, weighted by how many readings each day carries. Only days with recorded statistics appear."
        actions={<>
          <button className="button button-secondary" disabled title="This control is not wired to anything yet." data-testid="button-analytics-export"><Download size={14} />Export view</button>
        </>}
      />

      {trendQuery.isError && (
        <UnavailableNotice what="The network trend" error={trendQuery.error} onRetry={trendQuery.refetch} />
      )}

      {/*
        An average over zero points is not zero, and an average over points that
        never arrived is not an average at all. Both render as an em dash, and
        the sample count says which situation you are looking at.
      */}
      <div className="admin-kpis">
        <Metric icon={Thermometer} label="Avg temperature" value={avg((t) => t.temperature)} unit="°C" tone="teal" foot={derived(trendQuery, (t) => `${t.length} sample points`) as string} />
        <Metric icon={Droplets} label="Avg humidity" value={avg((t) => t.humidity)} unit="%" tone="blue" />
        <Metric icon={Wind} label="Avg air quality" value={avg((t) => t.airQuality)} unit="AQI" tone="amber" />
        <Metric icon={Gauge} label="Avg pressure" value={avg((t) => t.pressure)} unit="hPa" tone="blue" />
      </div>

      <div className="panel">
        <div className="panel-heading">
          <div><div className="eyebrow">NETWORK TIME SERIES</div><h3>Temperature, humidity, air quality</h3></div>
          <span className="panel-meta" title="Fleet daily averages, weighted by reading count. analytics-service stores daily aggregates only.">Fleet daily averages</span>
        </div>
        <DataState
          query={trendQuery}
          what="the network trend"
          empty={<div className="empty-inline">No trend data has been returned for this window.</div>}
        >
          {() => <EnvironmentalChart />}
        </DataState>
      </div>
    </>
  );
}
