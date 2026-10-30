import { useState } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { PackageCheck, UploadCloud } from 'lucide-react';
import { firmwareApi, type FirmwareRelease } from '@/api/firmwareApi';
import { deviceApi } from '@/api/deviceApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { ConfirmDialog } from '@/components/ConfirmDialog';
import { useToast } from '@/hooks/use-toast';
import { DataState, UnavailableNotice, derived } from '@/components/DataState';

export function Firmware() {
  const { toast } = useToast();
  const releasesQuery = useQuery({ queryKey: ['firmware'], queryFn: firmwareApi.list });
  const stationsQuery = useQuery({ queryKey: ['stations'], queryFn: deviceApi.list });
  const releases: FirmwareRelease[] = releasesQuery.data ?? [];
  const stations = stationsQuery.data ?? [];
  const [targetUid, setTargetUid] = useState('');
  const [pendingVersion, setPendingVersion] = useState<string | null>(null);

  const activeUid = targetUid || stations[0]?.uid || '';

  const push = useMutation({
    mutationFn: (version: string) => firmwareApi.requestUpdate(activeUid, version),
    onSuccess: (response) =>
      toast({
        title: 'Update requested',
        // Requested, not installed: the station applies it on its own schedule.
        description: response.message ?? `Version queued for ${activeUid}. The station applies it on its next window.`,
      }),
    onError: (error: Error) => toast({ title: 'Could not queue the update', description: error.message, variant: 'destructive' }),
  });

  return (
    <>
      <AdminPageHeader
        eyebrow="RELEASE MANAGEMENT · FIRMWARE"
        title="What is running in the field."
        description="Registered firmware releases and over-the-air update requests. Queueing an update sends the station a request; the station decides when it is safe to apply it."
        actions={<button className="button button-primary" disabled title="This control is not wired to anything yet." data-testid="button-register-firmware"><UploadCloud size={14} />Register release</button>}
      />

      {stationsQuery.isError && (
        <UnavailableNotice what="The station list" error={stationsQuery.error} onRetry={stationsQuery.refetch} />
      )}

      <div className="panel">
        <div className="panel-heading">
          <div><div className="eyebrow">FIRMWARE CATALOGUE</div><h3>{derived(releasesQuery, (r) => r.length)} registered release{releases.length === 1 ? '' : 's'}</h3></div>
          <select
            value={activeUid}
            onChange={(e) => setTargetUid(e.target.value)}
            aria-label="Station to queue firmware for"
            data-testid="select-firmware-target"
            style={{ background: 'transparent', border: '1px solid #d6e0d9', borderRadius: 6, padding: '6px 10px', fontSize: 13 }}
          >
            {stations.length
              ? stations.map((s) => <option key={s.uid} value={s.uid}>{s.name} — {s.uid}</option>)
              : <option value="">{stationsQuery.isSuccess ? 'No registered stations' : 'Stations unavailable'}</option>}
          </select>
        </div>

        <DataState
          query={releasesQuery}
          what="the firmware catalogue"
          empty={
            <div className="empty-state compact-empty">
              <UploadCloud size={21} /><h3>No firmware registered</h3>
              <p>Register a release with its version and binary location before queueing an over-the-air update.</p>
            </div>
          }
        >
          {(loaded) => loaded.map((release) => (
          <div className="simple-row" key={release.version} data-testid={`row-firmware-${release.version}`}>
            <span className="simple-row-index"><PackageCheck size={15} /></span>
            <span>
              <strong className="mono">{release.version}</strong>
              <small>{release.releaseNotes || 'No release notes recorded'}{release.createdAt ? ` · registered ${new Date(release.createdAt).toLocaleDateString()}` : ''}</small>
            </span>
            <button
              className="button button-secondary"
              disabled={!activeUid || push.isPending}
              onClick={() => setPendingVersion(release.version)}
              data-testid={`button-push-firmware-${release.version}`}
            >
              Queue for station
            </button>
          </div>
          ))}
        </DataState>
      </div>

      <ConfirmDialog
        open={pendingVersion !== null}
        onOpenChange={(open) => !open && setPendingVersion(null)}
        title={`Queue ${pendingVersion} for ${activeUid}?`}
        description="The station will download and install this firmware on its next update window. An interrupted install can take a station offline until it is serviced."
        confirmLabel="Queue update"
        destructive
        onConfirm={() => {
          const version = pendingVersion;
          setPendingVersion(null);
          if (version) push.mutate(version);
        }}
      />
    </>
  );
}
