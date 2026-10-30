import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { DeviceActivityFeed } from './DeviceActivityFeed';
import type { LiveDeviceEvent } from '@/hooks/use-live-stream';

/**
 * device-service published device.registered and both firmware events from the
 * day it was written, and nothing on the platform was bound to them, so the
 * broker discarded every one. They now reach the console over the live socket.
 *
 * The states below are kept apart deliberately: a closed socket is not a quiet
 * fleet, and showing an empty list for both would make the one case where
 * something could be happening unseen look identical to the case where nothing
 * is happening at all.
 */
describe('DeviceActivityFeed', () => {
  const registered: LiveDeviceEvent = {
    kind: 'deviceRegistered',
    deviceUid: 'ESP32-CHN-009',
    at: '2026-09-16T09:09:12.000Z',
    atIsReceipt: false,
    details: {},
  };

  it('shows an event it has actually seen', () => {
    render(<DeviceActivityFeed events={[registered]} status="open" />);

    expect(screen.getByTestId('activity-deviceRegistered-ESP32-CHN-009')).toBeInTheDocument();
    expect(screen.getByText('Station registered')).toBeInTheDocument();
  });

  it('says it is listening when connected with nothing yet to report', () => {
    render(<DeviceActivityFeed events={[]} status="open" />);

    expect(screen.getByTestId('text-activity-empty')).toHaveTextContent(/listening/i);
  });

  it('does not report a quiet fleet when the socket is closed', () => {
    render(<DeviceActivityFeed events={[]} status="closed" />);

    const note = screen.getByTestId('text-activity-unavailable');
    expect(note).toHaveTextContent(/not connected/i);
    // The distinction this component exists to preserve.
    expect(note).toHaveTextContent(/not a statement that the fleet is quiet/i);
    expect(screen.queryByTestId('text-activity-empty')).not.toBeInTheDocument();
  });

  it('distinguishes connecting from disconnected', () => {
    render(<DeviceActivityFeed events={[]} status="connecting" />);
    expect(screen.getByTestId('text-activity-unavailable')).toHaveTextContent(/connecting/i);
  });

  it('labels a receipt time so it cannot be read as when the event happened', () => {
    render(
      <DeviceActivityFeed
        events={[{ ...registered, atIsReceipt: true }]}
        status="open"
      />,
    );

    expect(screen.getByText(/^seen /)).toBeInTheDocument();
  });

  it('shows a firmware version when the event carried one, and nothing when it did not', () => {
    const { rerender } = render(
      <DeviceActivityFeed
        events={[{
          kind: 'firmwareCompleted',
          deviceUid: 'ESP32-CHN-009',
          at: '2026-09-16T09:10:00.000Z',
          atIsReceipt: false,
          details: { firmwareVersion: 'v2.8.4' },
        }]}
        status="open"
      />,
    );
    expect(screen.getByText('v2.8.4')).toBeInTheDocument();

    rerender(
      <DeviceActivityFeed
        events={[{
          kind: 'firmwareCompleted',
          deviceUid: 'ESP32-CHN-009',
          at: '2026-09-16T09:10:00.000Z',
          atIsReceipt: false,
          details: {},
        }]}
        status="open"
      />,
    );
    expect(screen.queryByText('v2.8.4')).not.toBeInTheDocument();
  });

  it('omits the time entirely rather than inventing one', () => {
    render(
      <DeviceActivityFeed
        events={[{ ...registered, at: undefined, atIsReceipt: false }]}
        status="open"
      />,
    );

    // No date is better than this client's clock passed off as the event's.
    expect(document.querySelector('.activity-list time')).toBeNull();
  });
});
