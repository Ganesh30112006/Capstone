import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { AlertTriangle, Check, MoreHorizontal, SlidersHorizontal } from 'lucide-react';
import { alertApi } from '@/api/alertApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge, toneForSeverity } from '@/components/StatusIndicators';
import { DataState } from '@/components/DataState';
import { UNKNOWN } from '@/lib/format';

export function Alerts() {
  const [filter, setFilter] = useState('active');
  const queryClient = useQueryClient();
  const alertsQuery = useQuery({ queryKey: ['alerts'], queryFn: alertApi.list });
  const allAlerts = alertsQuery.data ?? [];

  const acknowledge = useMutation({
    mutationFn: (id: string) => alertApi.acknowledge(id),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['alerts'] }),
  });

  // Tab counts are unknown until the queue has been read; a row of zeroes
  // reads as "nothing is wrong", which is precisely the claim we cannot make.
  const answered = alertsQuery.isSuccess;
  const countFor = (item: string) =>
    !answered ? UNKNOWN : item === 'all' ? allAlerts.length : allAlerts.filter((a) => a.status === item).length;

  return (
    <>
      <AdminPageHeader
        eyebrow="EVENTS · ALERT CENTER"
        title="Alerts with a paper trail."
        description="Review active conditions, acknowledgement state, and resolved history."
        actions={<button className="button button-primary" disabled title="This control is not wired to anything yet." data-testid="button-alert-rules"><SlidersHorizontal size={14} />Alert rules</button>}
      />
      <div className="panel alerts-panel">
        <div className="alert-tabs">
          {(['active', 'acknowledged', 'resolved', 'all'] as const).map((item) => (
            <button className={filter === item ? 'active' : ''} key={item} onClick={() => setFilter(item)} data-testid={`button-alert-tab-${item}`}>
              {item}<span>{countFor(item)}</span>
            </button>
          ))}
        </div>
        <DataState
          query={alertsQuery}
          what="the alert queue"
          empty={<div className="empty-state compact-empty"><Check size={22} /><h3>Nothing in this queue</h3><p>When an alert enters this state, it will appear here.</p></div>}
        >
          {(loaded) => {
            const alerts = loaded.filter((a) => filter === 'all' || a.status === filter);
            if (!alerts.length) {
              return <div className="empty-state compact-empty"><Check size={22} /><h3>Nothing in this queue</h3><p>When an alert enters this state, it will appear here.</p></div>;
            }
            return alerts.map((alert) => (
              <div className="alert-row" key={alert.id} data-testid={`row-alert-${alert.id}`}>
                <div className={`severity-mark ${alert.severity}`}><AlertTriangle size={15} /></div>
                <div className="alert-copy">
                  <div><strong>{alert.title}</strong><Badge tone={toneForSeverity(alert.severity)}>{alert.severity}</Badge></div>
                  <p>{alert.summary}</p>
                  <small>{alert.stationUid} · {alert.parameter} · {alert.timestamp}</small>
                </div>
                {alert.status === 'active' ? (
                  <button className="button button-secondary" onClick={() => acknowledge.mutate(alert.id)} disabled={acknowledge.isPending} data-testid={`button-alert-ack-${alert.id}`}>
                    Acknowledge
                  </button>
                ) : (
                  <button className="table-more" aria-label={`More actions for ${alert.title}`} disabled title="This control is not wired to anything yet." data-testid={`button-alert-more-${alert.id}`}><MoreHorizontal size={16} /></button>
                )}
              </div>
            ));
          }}
        </DataState>
      </div>
    </>
  );
}
