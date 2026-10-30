import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { connectLiveStream, type LiveFrame } from './live-stream';

/**
 * Minimal in-memory WebSocket stand-in so the client can be exercised without a
 * server. Tracks instances so a test can drive open/message/close.
 */
class FakeWebSocket {
  static instances: FakeWebSocket[] = [];
  static OPEN = 1;

  url: string;
  readyState = 0;
  sent: string[] = [];
  onopen: (() => void) | null = null;
  onmessage: ((e: { data: string }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: (() => void) | null = null;

  constructor(url: string) {
    this.url = url;
    FakeWebSocket.instances.push(this);
  }
  send(data: string) { this.sent.push(data); }
  close() { this.readyState = 3; this.onclose?.(); }

  simulateOpen() { this.readyState = 1; this.onopen?.(); }
  simulateMessage(obj: unknown) { this.onmessage?.({ data: JSON.stringify(obj) }); }
}

describe('connectLiveStream', () => {
  beforeEach(() => {
    FakeWebSocket.instances = [];
    vi.stubGlobal('WebSocket', FakeWebSocket as unknown as typeof WebSocket);
    localStorage.setItem('auth-token', 'test-jwt');
    vi.useFakeTimers();
  });

  afterEach(() => {
    vi.useRealTimers();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it('connects using the gateway ws URL with the token as a query parameter', () => {
    const handle = connectLiveStream({ onFrame: () => {} });
    const ws = FakeWebSocket.instances[0];

    expect(ws.url).toBe('ws://localhost:8443/ws/live?access_token=test-jwt');
    handle.close();
  });

  it('reports status transitions and forwards frames', () => {
    const frames: LiveFrame[] = [];
    const statuses: string[] = [];
    const handle = connectLiveStream({
      onFrame: (f) => frames.push(f),
      onStatus: (s) => statuses.push(s),
    });

    const ws = FakeWebSocket.instances[0];
    ws.simulateOpen();
    ws.simulateMessage({ type: 'telemetry', deviceUid: 'esp32-01', data: { temperature: 21 } });
    ws.simulateMessage({ type: 'pong' });

    expect(statuses).toContain('open');
    expect(frames).toHaveLength(1);
    expect(frames[0].deviceUid).toBe('esp32-01');
    handle.close();
  });

  it('sends periodic ping keep-alives while open', () => {
    const handle = connectLiveStream({ onFrame: () => {} });
    const ws = FakeWebSocket.instances[0];
    ws.simulateOpen();

    vi.advanceTimersByTime(26_000);

    expect(ws.sent.some((m) => m.includes('ping'))).toBe(true);
    handle.close();
  });

  it('reconnects with backoff after an unexpected close', () => {
    const handle = connectLiveStream({ onFrame: () => {} });
    const first = FakeWebSocket.instances[0];
    first.simulateOpen();
    first.close(); // server-side drop

    vi.advanceTimersByTime(1_000);

    expect(FakeWebSocket.instances).toHaveLength(2);
    handle.close();
  });

  it('does not reconnect after the caller closes it', () => {
    const handle = connectLiveStream({ onFrame: () => {} });
    FakeWebSocket.instances[0].simulateOpen();

    handle.close();
    vi.advanceTimersByTime(60_000);

    expect(FakeWebSocket.instances).toHaveLength(1);
  });

  it('goes to error status when there is no token', () => {
    localStorage.clear();
    const statuses: string[] = [];
    connectLiveStream({ onFrame: () => {}, onStatus: (s) => statuses.push(s) });

    expect(statuses).toContain('error');
    expect(FakeWebSocket.instances).toHaveLength(0);
  });
});
