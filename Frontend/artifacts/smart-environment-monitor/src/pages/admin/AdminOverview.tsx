import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { AlertTriangle, ArrowRight, Battery, ChevronRight, Download, Radio, Waves, WifiOff } from 'lucide-react';
import { deviceApi } from '@/api/deviceApi';
import { alertApi } from '@/api/alertApi';
import { authApi } from '@/api/authApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Metric } from '@/components/Metric';
import { EnvironmentalChart } from '@/components/EnvironmentalChart';
import { StationTable } from '@/components/StationTable';
import { DataState, UnavailableNotice, derived } from '@/components/DataState';
import type { Alert, Station } from '@/types/domain';

function greeting(): string {
  const hour = new Date().getHours();
  if (hour < 12) return 'Good morning';
  if (hour < 18) return 'Good afternoon';
  return 'Good evening';
}

const ATTENTION_LIMIT = 6;
const SEVERITY_RANK: Record<string, number> = { critical: 0, warning: 1, info: 2 };

export function AdminOverview() {
  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const alertsQuery = useQuery({ queryKey: ['alerts'], queryFn: alertApi.list });
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: authApi.me, retry: false });

  const stations: Station[] = stationsQuery.data ?? [];
  const alerts: Alert[] = alertsQuery.data ?? [];
  const activeAlerts = alerts.filter((a) => a.status === 'active');
  const operatorName = me?.data?.name?.split(' ')[0] ?? 'Operator';
  const today = new Date().toLocaleDateString(undefined, { weekday: 'long', day: '2-digit', month: 'short', year: 'numeric' }).toUpperCase();

  // The headline sentence is a claim about the network, so it only makes one
  // when the alert queue was actually read.
  const attentionLine = alertsQuery.isError
    ? 'Here is the state of the field network. The alert queue could not be read, so open items are unknown.'
    : alertsQuery.isPending
      ? 'Here is the state of the field network. Checking for open items…'
      : activeAlerts.length
        ? `Here is the state of the field network. ${activeAlerts.length} item${activeAlerts.length === 1 ? '' : 's'} need${activeAlerts.length === 1 ? 's' : ''} your attention.`
        : 'Here is the state of the field network. Nothing needs your attention right now.';

  return (
    <>
      <AdminPageHeader
        eyebrow={today}
        title={`${greeting()}, ${operatorName}.`}
        description={attentionLine}
        actions={<button className="button button-secondary" disabled title="This control is not wired to anything yet." data-testid="button-export-overview"><Download size={14} />Export snapshot</button>}
      />

      {stationsQuery.isError && (
        <UnavailableNotice what="The fleet" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}
      {alertsQuery.isError && (
        <UnavailableNotice what="The alert queue" error={alertsQuery.error} onRetry={alertsQuery.refetch} />
      )}

      {/*
        Every tile below is computed from a query that may not have answered.
        `derived` returns an em dash in that case, because a tile reading "0"
        beside a failure banner contradicts the banner. The two tiles that once
        read "99.4% uptime" and "97.8% sensor health" were hardcoded literals
        with nothing behind them; they are replaced by figures this console can
        actually account for.
      */}
      <div className="admin-kpis">
        <Metric
          icon={Radio}
          label="Reporting stations"
          value={derived(stationsQuery, (s) => `${s.filter((x) => x.status === 'online').length} / ${s.length}`)}
          tone="teal"
          foot={stations.length ? `${Math.round((stations.filter((s) => s.status === 'online').length / stations.length) * 100)}% of deployed fleet` : undefined}
        />
        <Metric icon={AlertTriangle} label="Active alerts" value={derived(alertsQuery, (a) => a.filter((x) => x.status === 'active').length)} tone="amber" />
        <Metric
          icon={Waves}
          label="Stations with a current reading"
          value={derived(stationsQuery, (s) => `${s.filter((x) => x.reading === 'ok').length} / ${s.length}`)}
          tone="blue"
          foot="Counted from the latest reading actually returned"
        />
        <Metric
          icon={WifiOff}
          label="Readings unavailable"
          value={derived(stationsQuery, (s) => s.filter((x) => x.reading === 'unavailable').length)}
          tone="red"
          foot="Stations whose latest reading could not be fetched"
        />
      </div>

      <div className="admin-overview-grid">
        <div className="panel overview-chart">
          <div className="panel-heading">
            <div><div className="eyebrow">NETWORK TELEMETRY</div><h3>Environmental trend</h3></div>
            <span className="panel-meta" title="Fleet daily averages, weighted by reading count.">Fleet daily averages</span>
          </div>
          <EnvironmentalChart compact />
        </div>

        <div className="panel attention-panel">
          <div className="panel-heading">
            <div><div className="eyebrow">NEEDS ATTENTION</div><h3>Open items</h3></div>
            <Link to="/admin/alerts" className="text-link">All alerts <ArrowRight size={13} /></Link>
          </div>
          <DataState
            query={alertsQuery}
            what="open items"
            // The failure is already reported by the banner above; repeating the
            // full notice inside every panel turns one outage into four alarms.
            unavailable={<div className="empty-inline">The alert queue could not be read, so open items are unknown.</div>}
            isEmpty={(items) => items.filter((a) => a.status === 'active').length === 0}
            empty={
              // A genuinely quiet queue can fall back to a maintenance hint.
              stations.filter((s) => s.battery !== null && s.battery < 40).slice(0, 1).map((s) => (
                <Link to={`/admin/devices/${s.uid}`} className="attention-row" key={s.uid}>
                  <span className="severity-mark info"><Battery size={14} /></span>
                  <span><strong>Battery at {s.battery}%</strong><small>{s.name} · service soon</small></span>
                  <ChevronRight size={14} />
                </Link>
              ))
            }
          >
            {(items) => {
              // The most severe few, not the whole queue: listing all 56 made this
              // panel taller than the page and stretched the chart beside it.
              const active = items.filter((a) => a.status === 'active')
                .sort((a, b) => SEVERITY_RANK[a.severity] - SEVERITY_RANK[b.severity]);
              const shown = active.slice(0, ATTENTION_LIMIT);
              return (
                <>
                  {shown.map((alert) => (
                    <Link to="/admin/alerts" className="attention-row" key={alert.id} data-testid={`row-overview-alert-${alert.id}`}>
                      <span className={`severity-mark ${alert.severity}`}><AlertTriangle size={14} /></span>
                      <span><strong>{alert.title}</strong><small>{alert.stationUid} · {alert.timestamp}</small></span>
                      <ChevronRight size={14} />
                    </Link>
                  ))}
                  {active.length > shown.length && (
                    <Link to="/admin/alerts" className="attention-row attention-more" data-testid="link-overview-more-alerts">
                      <span><strong>{active.length - shown.length} more in the alert queue</strong></span>
                      <ChevronRight size={14} />
                    </Link>
                  )}
                </>
              );
            }}
          </DataState>
        </div>

        <div className="panel station-table-panel">
          <div className="panel-heading">
            <div><div className="eyebrow">FLEET STATUS</div><h3>Stations in the field</h3></div>
            <Link to="/admin/devices" className="text-link">Manage fleet <ArrowRight size={13} /></Link>
          </div>
          <DataState
            query={stationsQuery}
            what="the fleet"
            unavailable={<div className="empty-inline">The fleet could not be read, so the stations in the field are unknown.</div>}
            empty={<div className="empty-inline">No stations are registered on this deployment yet.</div>}
          >
            {(data) => <StationTable stations={data} />}
          </DataState>
        </div>
      </div>
    </>
  );
}
