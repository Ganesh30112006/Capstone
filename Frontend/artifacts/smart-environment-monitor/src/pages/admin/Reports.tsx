import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { FileText, Sparkles } from 'lucide-react';
import { analyticsApi } from '@/api/analyticsApi';
import { aiApi } from '@/api/aiApi';
import { deviceApi } from '@/api/deviceApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { useToast } from '@/hooks/use-toast';
import { UnavailableNotice } from '@/components/DataState';

export function Reports() {
  const [period, setPeriod] = useState<'daily' | 'weekly'>('daily');
  const [deviceUid, setDeviceUid] = useState('');
  const queryClient = useQueryClient();
  const { toast } = useToast();

  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const fleetQuery = useQuery({ queryKey: ['fleet-report', period], queryFn: () => analyticsApi.getFleetReport(period) });
  const stations = stationsQuery.data ?? [];
  const fleetReport = fleetQuery.data;

  const activeUid = deviceUid || stations[0]?.uid || '';
  const aiReportQuery = useQuery({
    queryKey: ['ai-report', activeUid],
    queryFn: () => aiApi.getReport(activeUid),
    enabled: !!activeUid,
  });
  const aiReport = aiReportQuery.data;

  const generate = useMutation({
    mutationFn: () => aiApi.generateReport(activeUid),
    onSuccess: (report) => {
      queryClient.setQueryData(['ai-report', activeUid], report);
      toast({ title: 'Report generated', description: `Rolling summary for ${activeUid} rebuilt from the live stream window.` });
    },
    onError: (error: Error) => toast({ title: 'Could not generate report', description: error.message, variant: 'destructive' }),
  });

  return (
    <>
      <AdminPageHeader
        eyebrow="REPORTING"
        title="Summaries you can hand to someone."
        description="Fleet-level rollups from the analytics service, plus per-station rolling summaries built by the AI stream worker."
        actions={
          <div className="segmented">
            {(['daily', 'weekly'] as const).map((p) => (
              <button key={p} className={period === p ? 'active' : ''} onClick={() => setPeriod(p)} data-testid={`button-report-period-${p}`}>{p}</button>
            ))}
          </div>
        }
      />
      {stationsQuery.isError && (
        <UnavailableNotice what="The station list" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}
      <div className="admin-generic-grid">
        <div className="panel generic-hero" style={{ height: 'auto', padding: '2rem' }}>
          <div className="generic-icon"><FileText size={22} /></div>
          <div className="eyebrow">FLEET {period.toUpperCase()} REPORT</div>
          {fleetQuery.isPending ? (
            <p>Loading the fleet report…</p>
          ) : fleetQuery.isError ? (
            /* "No report yet" is a schedule fact; "could not be fetched" is
               an outage. The old code printed the first message for both. */
            <p data-testid="fleet-report-unavailable">The fleet report could not be fetched, so whether one exists for this period is unknown.</p>
          ) : fleetReport ? (
            <>
              <h2>{fleetReport.reportId}</h2>
              <p>Generated {new Date(fleetReport.generatedAt).toLocaleString()}</p>
              <div className="generic-stat-row" style={{ marginTop: '20px' }}>
                <span><small>Total devices</small><strong>{fleetReport.summary?.totalDevices ?? '—'}</strong></span>
                <span><small>Active alerts</small><strong>{fleetReport.summary?.activeAlerts ?? '—'}</strong></span>
                <span><small>Avg temperature</small><strong>{fleetReport.summary?.averageTemperature ?? '—'}°C</strong></span>
              </div>
            </>
          ) : (
            <p>No fleet report is available for this period yet.</p>
          )}
        </div>

        <div className="panel generic-list">
          <div className="panel-heading">
            <div><div className="eyebrow">STATION SUMMARY · AI STREAM</div><h3>Rolling report</h3></div>
            <select value={activeUid} onChange={(e) => setDeviceUid(e.target.value)} aria-label="Station to report on" data-testid="select-report-station" style={{ background: 'transparent', border: '1px solid #d6e0d9', borderRadius: 6, padding: '6px 10px', fontSize: 13 }}>
              {stations.length
                ? stations.map((s) => <option key={s.uid} value={s.uid}>{s.name}</option>)
                : <option value="">{stationsQuery.isSuccess ? 'No registered stations' : 'Stations unavailable'}</option>}
            </select>
          </div>
          {aiReportQuery.isError ? (
            <div className="empty-inline" data-testid="ai-report-unavailable">The rolling summary for this station could not be fetched — which is not the same as there not being one.</div>
          ) : aiReport ? (
            <div style={{ padding: '0 1.25rem 1.25rem' }}>
              <p>{aiReport.narrative}</p>
              <div className="generic-stat-row">
                <span><small>Samples</small><strong>{aiReport.sampleCount}</strong></span>
                <span><small>Anomalies</small><strong>{aiReport.anomalyCount}</strong></span>
                <span><small>Window ends</small><strong>{new Date(aiReport.windowEnd).toLocaleTimeString()}</strong></span>
              </div>
              {Object.entries(aiReport.metrics ?? {}).map(([metric, stats]) => (
                <div className="simple-row" key={metric}>
                  <span><strong>{metric}</strong><small>min {stats.min} · avg {stats.avg} · max {stats.max}</small></span>
                  <span className="mono">{stats.last}</span>
                </div>
              ))}
            </div>
          ) : (
            <div className="empty-inline">No rolling summary has been generated for this station yet.</div>
          )}
          <div style={{ padding: '0 1.25rem 1.25rem' }}>
            <button className="button button-primary" onClick={() => generate.mutate()} disabled={!activeUid || generate.isPending} data-testid="button-generate-report">
              <Sparkles size={14} />{generate.isPending ? 'Generating…' : 'Generate now'}
            </button>
          </div>
        </div>
      </div>
    </>
  );
}
