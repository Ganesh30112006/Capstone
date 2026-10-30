import { useQuery } from '@tanstack/react-query';
import { ExternalLink, RefreshCw, ShieldCheck } from 'lucide-react';
import { systemApi } from '@/api/systemApi';
import { streamApi } from '@/api/streamApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge, StatusDot } from '@/components/StatusIndicators';
import type { ServiceHealth } from '@/types/domain';
import { UNKNOWN } from '@/lib/format';

export function SystemHealth() {
  const { data: services = [] as ServiceHealth[], refetch, isFetching, isError, isSuccess, isPending } = useQuery({
    queryKey: ['services'],
    queryFn: systemApi.getServiceHealth,
    refetchInterval: 30_000,
  });
  const { data: stream } = useQuery({ queryKey: ['stream-status'], queryFn: streamApi.getStatus });

  // The broker's management UI lives outside the gateway, so its address is a
  // deployment fact supplied at build time. Unset, no link is offered rather
  // than one that goes nowhere.
  const brokerUiUrl = import.meta.env.VITE_RABBITMQ_UI_URL?.trim();

  const outages = services.filter((s) => s.status === 'outage');
  const degraded = services.filter((s) => s.status === 'degraded');

  // "All clear" is only ever claimed on the strength of a probe that actually
  // ran and actually returned something. The three states below are distinct
  // and were previously collapsed into one: a failed request produced an empty
  // list, an empty list contained no outages, and no outages rendered as
  // healthy — so an unreachable gateway looked identical to a healthy fleet.
  const summary = isPending
    ? { tone: 'warn' as const, title: 'Checking service health…', note: 'The first probe has not returned yet, so nothing here has been established.' }
    : isError
    ? { tone: 'bad' as const, title: 'Service health could not be reached', note: 'The gateway did not answer the health probe, so the state of every service below is unknown — not healthy.' }
    : outages.length
      ? { tone: 'bad' as const, title: `${outages.length} service${outages.length === 1 ? '' : 's'} unreachable`, note: 'Telemetry for the affected paths may be delayed or missing.' }
      : degraded.length
        ? { tone: 'warn' as const, title: `${degraded.length} service${degraded.length === 1 ? '' : 's'} degraded`, note: 'A non-blocking degradation is being reported by the platform.' }
        : isSuccess && services.length
          ? { tone: 'good' as const, title: 'All services operational', note: 'Every probed service reported healthy on its last check.' }
          : { tone: 'warn' as const, title: 'No services were probed', note: 'The gateway answered but reported no services, so nothing here has been checked.' };

  return (
    <>
      <AdminPageHeader
        eyebrow="PLATFORM · SERVICE HEALTH"
        title="The machinery behind the signal."
        description="Live probe results for ingestion, registry, alerting, analytics, streaming, and inference. Probes report status and latency only."
        actions={
          <>
            {brokerUiUrl && <a className="button button-secondary" href={brokerUiUrl} target="_blank" rel="noopener noreferrer" data-testid="link-rabbitmq-ui"><ExternalLink size={14} />RabbitMQ console</a>}
            <button className="button button-secondary" onClick={() => refetch()} disabled={isFetching} data-testid="button-run-health-check"><RefreshCw size={14} />{isFetching ? 'Checking…' : 'Run health check'}</button>
          </>
        }
      />

      <div className="system-summary">
        <div className="system-summary-icon"><ShieldCheck size={22} /></div>
        <div><strong data-testid="system-summary-title">{summary.title}</strong><p>{summary.note}</p></div>
        {/* "0 probed" beside "could not be reached" contradicts the banner. */}
        <Badge tone={summary.tone}>{isSuccess ? services.length : UNKNOWN} probed</Badge>
      </div>

      {stream && (
        <div className="callout info" style={{ marginBottom: '1.5rem' }}>
          <StatusDot status={stream.connected ? 'online' : 'offline'} />
          <div>
            <strong>Live stream · {stream.source === 'aws' ? 'AWS IoT Core' : 'local message bus'}</strong>
            <p>{stream.connected ? 'Connected' : 'Not connected'} · {stream.activeClients} console client{stream.activeClients === 1 ? '' : 's'} attached · topic prefix {stream.topicPrefix}</p>
          </div>
        </div>
      )}

      <div className="service-grid">
        {services.map((service) => (
          <div className="panel service-card" key={service.name} data-testid={`card-service-${service.name}`}>
            <div className="service-status">
              <StatusDot status={service.status} />
              <Badge tone={service.status === 'operational' ? 'good' : service.status === 'degraded' ? 'warn' : 'bad'}>{service.status}</Badge>
            </div>
            <h3>{service.name}</h3>
            <div className="service-meta">
              <span>Response<strong className="mono">{service.lastCheck}</strong></span>
            </div>
          </div>
        ))}
        {!services.length && <div className="empty-inline">{isError ? 'The health probe failed, so no service states could be read.' : 'No health data has been returned by the gateway.'}</div>}
      </div>
    </>
  );
}
