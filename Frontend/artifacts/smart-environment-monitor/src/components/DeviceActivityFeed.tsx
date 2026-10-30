import { Radio, ShieldCheck, Download, CheckCircle2 } from 'lucide-react';
import type { LiveDeviceEvent } from '@/hooks/use-live-stream';
import type { LiveStatus } from '@/lib/live-stream';

const LABELS: Record<LiveDeviceEvent['kind'], string> = {
  deviceRegistered: 'Station registered',
  firmwareRequested: 'Firmware rollout started',
  firmwareCompleted: 'Firmware rollout finished',
};

const ICONS: Record<LiveDeviceEvent['kind'], typeof Radio> = {
  deviceRegistered: ShieldCheck,
  firmwareRequested: Download,
  firmwareCompleted: CheckCircle2,
};

function clockTime(at: string | undefined): string | null {
  if (!at) return null;
  const parsed = new Date(at);
  return Number.isNaN(parsed.getTime()) ? null : parsed.toLocaleTimeString();
}

/**
 * Device lifecycle events as they arrive on the live socket.
 *
 * This feed reports only what it has actually seen on an open socket, for as
 * long as it has been open. That is a narrower claim than it looks, and the
 * empty states keep it honest: a closed socket says so rather than showing an
 * empty list, because "nothing has happened" and "we are not listening" would
 * otherwise render identically — and the second one is the case where
 * something could be happening unseen.
 *
 * It is a live tail, never a history. Events raised before this page was opened
 * were not missed by the platform, which has them in the device record and the
 * firmware history; they were missed by this socket.
 */
export function DeviceActivityFeed({
  events,
  status,
}: {
  events: LiveDeviceEvent[];
  status: LiveStatus;
}) {
  return (
    <section className="panel activity-panel" data-testid="panel-device-activity">
      <header className="activity-header">
        <h2>
          <Radio size={15} /> Live activity
        </h2>
        <p>Registrations and firmware rollouts, as they happen.</p>
      </header>

      {status !== 'open' ? (
        <p className="activity-note" data-testid="text-activity-unavailable">
          {status === 'connecting'
            ? 'Connecting to the live stream…'
            : 'Not connected to the live stream, so nothing can be reported here. This is not a statement that the fleet is quiet.'}
        </p>
      ) : events.length === 0 ? (
        <p className="activity-note" data-testid="text-activity-empty">
          Listening. No registrations or firmware rollouts since this page was opened.
        </p>
      ) : (
        <ul className="activity-list">
          {events.map((event, index) => {
            const Icon = ICONS[event.kind];
            const time = clockTime(event.at);
            const version = event.details?.firmwareVersion;
            return (
              <li
                key={`${event.deviceUid}-${event.kind}-${event.at ?? index}`}
                data-testid={`activity-${event.kind}-${event.deviceUid}`}
              >
                <Icon size={15} />
                <div className="activity-body">
                  <strong>{LABELS[event.kind]}</strong>
                  <span className="activity-device">{event.deviceUid}</span>
                  {typeof version === 'string' && (
                    <span className="activity-version">{version}</span>
                  )}
                </div>
                {/* A receipt time is this client's clock, not the event's, and
                    is labelled so it cannot be read as when it happened. */}
                {time && (
                  <time dateTime={event.at}>
                    {event.atIsReceipt ? `seen ${time}` : time}
                  </time>
                )}
              </li>
            );
          })}
        </ul>
      )}
    </section>
  );
}
