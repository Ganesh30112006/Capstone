import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { AlertTriangle, CalendarClock, Download, HeartPulse, HelpCircle, X } from 'lucide-react';
import { sensorApi } from '@/api/sensorApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Metric } from '@/components/Metric';
import { SensorCard } from '@/components/SensorCard';
import { CalibrationDialog } from '@/components/CalibrationDialog';
import { DataState, UnavailableNotice, derived } from '@/components/DataState';
import type { Sensor } from '@/types/domain';

const FILTERS = ['all', 'healthy', 'degraded', 'failed', 'unknown'] as const;

/*
 * Worst first: an operator opens this page to find what needs attention, and
 * in station order the four degraded probes of one station sat at the bottom
 * of thirty-five cards.
 */
const HEALTH_RANK: Record<Sensor['health'], number> = { failed: 0, degraded: 1, unknown: 2, healthy: 3 };
const CALIBRATION_RANK: Record<string, number> = { EXPIRED: 0, DUE_SOON: 1, NOT_CALIBRATED: 2, VALID: 3 };

function byAttention(a: Sensor, b: Sensor): number {
  return HEALTH_RANK[a.health] - HEALTH_RANK[b.health]
    || (CALIBRATION_RANK[a.calibration?.status ?? ''] ?? 4) - (CALIBRATION_RANK[b.calibration?.status ?? ''] ?? 4)
    || (a.healthScore?.percent ?? 101) - (b.healthScore?.percent ?? 101)
    || a.stationUid.localeCompare(b.stationUid)
    || a.name.localeCompare(b.name);
}

export function Sensors() {
  const [filter, setFilter] = useState<(typeof FILTERS)[number]>('all');
  const [calibrating, setCalibrating] = useState<Sensor | null>(null);
  const inventoryQuery = useQuery({ queryKey: ['sensors'], queryFn: sensorApi.getSensors, refetchInterval: 15_000 });
  const inventory = inventoryQuery.data;
  const allSensors = inventory?.sensors ?? [];
  const unreadable = inventory?.unreadable ?? [];
  const unevaluated = inventory?.unevaluated ?? [];

  return (
    <>
      <AdminPageHeader
        eyebrow="INSTRUMENTATION · SENSOR HEALTH"
        title="Listen to every probe."
        description="Each sensor's health over the last day, its calibration record, and the checks behind both."
        actions={<button className="button button-secondary" disabled title="This control is not wired to anything yet." data-testid="button-export-sensors"><Download size={14} />Export diagnostics</button>}
      />

      {/*
        Stations whose diagnostics failed are named rather than dropped. Before,
        their probes simply did not appear, so a fleet with four unreachable
        stations and no failures looked the same as a fleet in perfect health.
      */}
      {unreadable.length > 0 && (
        <UnavailableNotice
          what={`Diagnostics for ${unreadable.length} station${unreadable.length === 1 ? '' : 's'}`}
          detail={`${unreadable.join(', ')} did not answer their health probe, so their sensors are absent from the counts and the list below.`}
          onRetry={inventoryQuery.refetch}
        />
      )}

      {/* A different statement: these stations answered, and have simply never been evaluated. */}
      {unevaluated.length > 0 && (
        <div className="callout warn unevaluated-notice" data-testid="notice-unevaluated-stations">
          <HelpCircle size={17} />
          <div>
            <strong>
              {unevaluated.length} station{unevaluated.length === 1 ? ' has' : 's have'} no diagnostics on file
            </strong>
            <p>
              {unevaluated.join(', ')} {unevaluated.length === 1 ? 'has' : 'have'} never been evaluated, so no probe
              status is known for {unevaluated.length === 1 ? 'it' : 'them'}. That is not the same as a clean bill of
              health, and {unevaluated.length === 1 ? 'it is' : 'they are'} excluded from the counts below.
            </p>
          </div>
        </div>
      )}

      <div className="admin-kpis">
        <Metric icon={HeartPulse} label="Healthy sensors" value={derived(inventoryQuery, (i) => i.sensors.filter((s) => s.health === 'healthy').length)} unit={inventory ? `/ ${allSensors.length}` : undefined} tone="teal" />
        <Metric icon={AlertTriangle} label="Degraded" value={derived(inventoryQuery, (i) => i.sensors.filter((s) => s.health === 'degraded').length)} tone="amber" />
        <Metric icon={X} label="Failed" value={derived(inventoryQuery, (i) => i.sensors.filter((s) => s.health === 'failed').length)} tone="red" />
        <Metric
          icon={CalendarClock}
          label="Calibration due"
          value={derived(inventoryQuery, (i) => i.sensors.filter((s) => s.calibration?.status === 'EXPIRED' || s.calibration?.status === 'DUE_SOON').length)}
          tone="blue"
          foot="Expired, or expiring within 14 days"
        />
      </div>

      <div className="panel">
        <div className="admin-toolbar">
          <div className="eyebrow">ALL SENSORS · {inventory ? `${allSensors.length} TOTAL` : 'COUNT UNKNOWN'}</div>
          <div className="segmented" role="group" aria-label="Filter by health">
            {FILTERS.map((item) => (
              <button
                className={filter === item ? 'active' : ''}
                aria-pressed={filter === item}
                key={item}
                onClick={() => setFilter(item)}
                data-testid={`button-sensor-filter-${item}`}
              >
                {item}
              </button>
            ))}
          </div>
        </div>
        <DataState
          query={inventoryQuery}
          what="sensor diagnostics"
          isEmpty={(i) => i.sensors.length === 0}
          empty={<div className="empty-inline">No sensors have reported diagnostics on this deployment yet.</div>}
        >
          {(loaded) => {
            const sensors = loaded.sensors.filter((s) => filter === 'all' || s.health === filter).sort(byAttention);
            if (!sensors.length) return <div className="empty-inline">No sensors match this filter.</div>;
            return (
              <div className="sensor-cards">
                {sensors.map((sensor) => (
                  <SensorCard key={`${sensor.stationUid}-${sensor.name}`} sensor={sensor} onCalibrate={setCalibrating} />
                ))}
              </div>
            );
          }}
        </DataState>
      </div>

      <CalibrationDialog sensor={calibrating} open={calibrating !== null} onOpenChange={(open) => { if (!open) setCalibrating(null); }} />
    </>
  );
}
