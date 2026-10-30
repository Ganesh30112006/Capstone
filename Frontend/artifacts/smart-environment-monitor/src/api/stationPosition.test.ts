import { describe, it, expect, vi, beforeEach } from 'vitest';
import { httpClient } from './httpClient';
import { sensorApi } from './sensorApi';
import { deviceApi } from './deviceApi';

vi.mock('./httpClient', async () => {
  const actual = await vi.importActual<typeof import('./httpClient')>('./httpClient');
  return { ...actual, httpClient: { get: vi.fn(), post: vi.fn() } };
});

/**
 * Every reading already carried the station's own latitude and longitude, and
 * this client fetched them on every fleet load and then dropped them: the map
 * has only ever drawn the registry's copy. The registry says where a station
 * was recorded as being, which stays put when the station does not.
 */
describe('which position a station is plotted from', () => {
  const REGISTERED = { latitude: 15.5057, longitude: 80.0499 };

  beforeEach(() => {
    vi.clearAllMocks();
  });

  function fleet(device: Record<string, unknown>) {
    vi.mocked(httpClient.get).mockResolvedValue({
      data: { data: [{ deviceUid: 'ESP32-CHN-001', deviceName: 'Chirala', ...device }] },
    } as never);
  }

  function reports(reading: Record<string, unknown> | null) {
    vi.spyOn(sensorApi, 'getLatestForEach').mockResolvedValue(
      reading ? { 'ESP32-CHN-001': reading as never } : {},
    );
  }

  it('prefers what the station reported over what the registry holds', async () => {
    fleet(REGISTERED);
    // The station is a kilometre or so down the coast from where it was filed.
    reports({ latitude: 15.515, longitude: 80.06 });

    const [station] = await deviceApi.list();

    expect(station.position).toEqual({
      latitude: 15.515,
      longitude: 80.06,
      source: 'reported',
    });
  });

  it('falls back to the registry when the station has reported no position', async () => {
    fleet(REGISTERED);
    reports({ temperature: 31.2 });

    const [station] = await deviceApi.list();

    expect(station.position).toEqual({ ...REGISTERED, source: 'registered' });
    // Nothing to compare against, so no drift is claimed.
    expect(station.positionDriftMetres).toBeNull();
  });

  it('measures how far a reported position sits from the registered one', async () => {
    fleet(REGISTERED);
    reports({ latitude: 15.515, longitude: 80.06 });

    const [station] = await deviceApi.list();

    expect(station.positionDriftMetres).toBeGreaterThan(1_000);
    expect(station.positionDriftMetres).toBeLessThan(2_500);
  });

  it('ignores a GPS that has not got a fix', async () => {
    fleet(REGISTERED);
    // (0, 0) is the Gulf of Guinea. Trusting it would move a coastal Andhra
    // station into the Atlantic and make a failed fix look like a working one.
    reports({ latitude: 0, longitude: 0 });

    const [station] = await deviceApi.list();

    expect(station.position).toEqual({ ...REGISTERED, source: 'registered' });
    expect(station.positionDriftMetres).toBeNull();
  });

  it('leaves a station unplaced when neither source has a position', async () => {
    fleet({ latitude: null, longitude: null });
    reports({ temperature: 31.2 });

    const [station] = await deviceApi.list();

    // Not plotted, and not quietly dropped either: the map counts it.
    expect(station.position).toBeNull();
    expect(station.positionDriftMetres).toBeNull();
  });

  it('still plots a reported position when the registry has none', async () => {
    fleet({ latitude: null, longitude: null });
    reports({ latitude: 15.515, longitude: 80.06 });

    const [station] = await deviceApi.list();

    expect(station.position?.source).toBe('reported');
    expect(station.positionDriftMetres).toBeNull();
  });

  it('keeps the registry position on the station regardless of what is plotted', async () => {
    fleet(REGISTERED);
    reports({ latitude: 15.515, longitude: 80.06 });

    const [station] = await deviceApi.list();

    // The registered pair stays readable; the plotted one is a separate field.
    expect(station.latitude).toBe(REGISTERED.latitude);
    expect(station.longitude).toBe(REGISTERED.longitude);
  });
});
