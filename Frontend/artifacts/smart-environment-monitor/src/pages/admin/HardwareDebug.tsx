import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Activity, BarChart3, RefreshCw, ShieldAlert, UploadCloud } from 'lucide-react';
import { deviceApi } from '@/api/deviceApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge } from '@/components/StatusIndicators';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { UnavailableNotice } from '@/components/DataState';
import type { DeviceCommandName } from '@/types/domain';
import type { LucideProps } from 'lucide-react';
import type { ComponentType } from 'react';

interface CommandDescriptor {
  name: DeviceCommandName;
  label: string;
  icon: ComponentType<LucideProps>;
  /** Disruptive commands must be confirmed before they are dispatched. */
  confirm?: string;
}

const COMMANDS: CommandDescriptor[] = [
  { name: 'PING', label: 'Request health ping', icon: Activity },
  { name: 'TELEMETRY_SYNC', label: 'Force telemetry sync', icon: BarChart3 },
  { name: 'REBOOT', label: 'Submit reboot sequence', icon: RefreshCw, confirm: 'The station will restart. Readings will be missing until it reconnects, and firmware decides when it is safe to comply.' },
  { name: 'OTA_CHECK', label: 'Queue firmware check', icon: UploadCloud, confirm: 'The station will check for and apply a pending firmware update on its next window.' },
];

export function HardwareDebug() {
  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const stations = stationsQuery.data ?? [];
  const [stationUid, setStationUid] = useState('');
  const [log, setLog] = useState('Console ready. Commands are dispatched through the API Gateway to AWS IoT Core.\n');
  const [pending, setPending] = useState<CommandDescriptor | null>(null);
  const [isSending, setIsSending] = useState(false);

  const activeUid = stationUid || stations[0]?.uid || '';

  const append = (line: string) => setLog((prev) => `${prev}\n[${new Date().toISOString()}] ${line}`);

  const dispatch = async (command: CommandDescriptor) => {
    if (!activeUid) return;
    setIsSending(true);
    append(`Publishing ${command.name} to ${activeUid} via device-service…`);
    try {
      const response = await deviceApi.sendCommand(activeUid, command.name);
      // Deliberately says published, not acknowledged: the broker accepted it,
      // the station has not necessarily executed it.
      append(`PUBLISHED: ${command.name} accepted by the broker. ${response.message ?? ''}`.trim());
    } catch (error) {
      append(`FAILED: ${error instanceof Error ? error.message : 'Command could not be dispatched.'}`);
    } finally {
      setIsSending(false);
    }
  };

  const onCommandClick = (command: CommandDescriptor) => {
    if (command.confirm) {
      setPending(command);
    } else {
      void dispatch(command);
    }
  };

  return (
    <>
      <AdminPageHeader
        eyebrow="OPERATIONS · HARDWARE DEBUG"
        title="Secure hardware link"
        description="Dispatch one of the four operations the platform supports. Every command is authorised at the gateway, recorded by device-service, and published to the station over AWS IoT Core MQTT. The browser never talks to hardware directly, and there is no free-text command path."
      />

      {/* An empty target list must not read as "there is no hardware to
          command" when what it means is "the fleet could not be listed". */}
      {stationsQuery.isError && (
        <UnavailableNotice what="The station list" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}

      <div className="panel alerts-panel" style={{ padding: '1.5rem', marginBottom: '1rem' }}>
        <div style={{ display: 'flex', gap: '10px', alignItems: 'flex-end', marginBottom: '1.5rem', flexWrap: 'wrap' }}>
          <div style={{ display: 'flex', flexDirection: 'column', gap: '4px', flex: 1, minWidth: '220px' }}>
            <label htmlFor="station-select" style={{ fontSize: '11px', fontWeight: 600, color: '#71807b' }}>Target station</label>
            <select
              id="station-select"
              value={activeUid}
              onChange={(e) => setStationUid(e.target.value)}
              style={{ padding: '8px', border: '1px solid #dfe7e1', borderRadius: '4px', background: 'transparent' }}
              data-testid="select-hardware-station"
            >
              {stations.length
                ? stations.map((s) => <option key={s.uid} value={s.uid}>{s.name} — {s.uid}</option>)
                : <option value="">{stationsQuery.isSuccess ? 'No registered stations' : 'Stations unavailable'}</option>}
            </select>
          </div>
          <Badge tone="info">Commands limited to the supported set</Badge>
        </div>

        <div style={{ display: 'flex', gap: '10px', flexWrap: 'wrap' }}>
          {COMMANDS.map((command) => {
            const Icon = command.icon;
            return (
              <button
                key={command.name}
                className="button button-secondary"
                disabled={isSending || !activeUid}
                onClick={() => onCommandClick(command)}
                data-testid={`button-command-${command.name}`}
              >
                <Icon size={14} /> {command.label}
              </button>
            );
          })}
        </div>
      </div>

      <div className="panel console-log" style={{ backgroundColor: '#111815', color: '#10b981', padding: '1.5rem', fontFamily: 'monospace', borderRadius: '8px', fontSize: '12px' }}>
        <div style={{ whiteSpace: 'pre-wrap', minHeight: '200px' }} data-testid="hardware-debug-log">{log}</div>
      </div>

      <ConfirmDialog
        open={pending !== null}
        onOpenChange={(open) => !open && setPending(null)}
        title={pending ? `Send ${pending.name} to ${activeUid}?` : ''}
        description={
          <>
            <ShieldAlert size={15} style={{ verticalAlign: 'text-bottom', marginRight: 6 }} />
            {pending?.confirm}
          </>
        }
        confirmLabel={pending ? `Send ${pending.name}` : 'Send'}
        destructive
        onConfirm={() => {
          const command = pending;
          setPending(null);
          if (command) void dispatch(command);
        }}
      />
    </>
  );
}
