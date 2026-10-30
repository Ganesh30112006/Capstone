import { useQuery } from '@tanstack/react-query';
import { Settings as SettingsIcon } from 'lucide-react';
import { authApi } from '@/api/authApi';
import { streamApi } from '@/api/streamApi';
import { API_BASE_URL } from '@/api/httpClient';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge } from '@/components/StatusIndicators';
import { UnavailableNotice } from '@/components/DataState';
import { UNKNOWN } from '@/lib/format';

export function Settings() {
  const { data: me } = useQuery({ queryKey: ['me'], queryFn: authApi.me, retry: false });
  const streamQuery = useQuery({ queryKey: ['stream-status'], queryFn: streamApi.getStatus });
  const stream = streamQuery.data;
  const user = me?.data;

  return (
    <>
      <AdminPageHeader
        eyebrow="CONSOLE CONFIGURATION · SETTINGS"
        title="How this console is wired."
        description="The account you are signed in as, and the endpoints this browser talks to. Nothing here is a secret — the console never holds service credentials."
      />

      {streamQuery.isError && (
        <UnavailableNotice what="Live stream status" error={streamQuery.error} onRetry={streamQuery.refetch} />
      )}

      <div className="admin-generic-grid">
        <div className="panel generic-hero" style={{ height: 'auto', padding: '2rem' }}>
          <div className="generic-icon"><SettingsIcon size={22} /></div>
          <div className="eyebrow">SIGNED IN AS</div>
          <h2>{user?.name ?? 'Loading…'}</h2>
          <p>{user?.email}</p>
          <div className="generic-stat-row" style={{ marginTop: '20px' }}>
            <span><small>Role</small><strong><Badge tone={user?.role === 'ADMIN' ? 'good' : 'neutral'}>{user?.role ?? '—'}</Badge></strong></span>
            <span><small>Session</small><strong>Bearer token in browser storage</strong></span>
          </div>
        </div>

        <div className="panel generic-list">
          <div className="panel-heading">
            <div><div className="eyebrow">CONNECTIONS</div><h3>Where requests go</h3></div>
          </div>
          <div className="simple-row">
            <span><strong>API Gateway</strong><small>Every request is routed through it; no service is addressed directly.</small></span>
            <span className="mono">{API_BASE_URL}</span>
          </div>
          <div className="simple-row">
            {/* The old copy asserted "mirrored from the local message bus"
                whenever the source was anything other than 'aws' — including
                when the status had never been read at all. */}
            <span><strong>Live stream source</strong><small>{!streamQuery.isSuccess ? 'The stream status has not been read.' : !stream ? 'This deployment has no live-stream branch.' : stream.source === 'aws' ? 'Subscribed to AWS IoT Core over MQTT' : 'Mirrored from the local message bus'}</small></span>
            <span className="mono">{stream?.source ?? UNKNOWN}</span>
          </div>
          <div className="simple-row">
            <span><strong>Console clients attached</strong><small>Browsers currently receiving live frames</small></span>
            <span className="mono">{stream?.activeClients ?? UNKNOWN}</span>
          </div>
        </div>
      </div>
    </>
  );
}
