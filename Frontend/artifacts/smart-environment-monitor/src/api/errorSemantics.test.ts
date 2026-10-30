import { describe, it, expect, vi, beforeEach } from 'vitest';
import { ApiError, absentAsNull, httpClient } from './httpClient';
import { deviceApi } from './deviceApi';
import { alertApi } from './alertApi';
import { userApi } from './userApi';
import { firmwareApi } from './firmwareApi';
import { analyticsApi } from './analyticsApi';
import { aiApi } from './aiApi';
import { sensorApi } from './sensorApi';

/**
 * The api/* layer's one rule: a failure may only be reported as "there is
 * nothing there" when the backend actually said so.
 *
 * Sixteen of the eighteen catch blocks in this directory used to discard the
 * error and return `[]` or `null`. Every one of those turned an unreachable
 * gateway into a confident, empty, healthy-looking console — an empty fleet on
 * the public map, an empty alert queue on the operator dashboard, an empty
 * account list on the access-control page. These tests exist to keep that
 * shortcut from coming back one module at a time.
 */

const get = vi.spyOn(httpClient, 'get');

function reject(status: number | undefined) {
  return () => Promise.reject(new ApiError('boom', status));
}

function resolve(body: unknown) {
  return () => Promise.resolve({ data: body } as never);
}

beforeEach(() => {
  get.mockReset();
});

describe('absentAsNull', () => {
  it('treats a 404 as a genuine absence', () => {
    expect(absentAsNull(new ApiError('missing', 404))).toBeNull();
  });

  it('rethrows a server error, which says nothing about whether the thing exists', () => {
    expect(() => absentAsNull(new ApiError('server', 500))).toThrow(ApiError);
  });

  it('rethrows an unreachable gateway', () => {
    // No status at all: the request never got an answer.
    expect(() => absentAsNull(new ApiError('Could not reach the API Gateway.', undefined))).toThrow();
  });

  it('rethrows anything that is not an ApiError, rather than swallowing it', () => {
    expect(() => absentAsNull(new TypeError('unexpected'))).toThrow(TypeError);
  });
});

describe('collection endpoints propagate failure instead of returning an empty list', () => {
  const cases: Array<[string, () => Promise<unknown>]> = [
    ['deviceApi.list', () => deviceApi.list()],
    ['alertApi.list', () => alertApi.list()],
    ['userApi.list', () => userApi.list()],
    ['firmwareApi.list', () => firmwareApi.list()],
    ['analyticsApi.getNetworkTrend', () => analyticsApi.getNetworkTrend()],
    ['analyticsApi.getHeatmap', () => analyticsApi.getHeatmap('temperature')],
    ['aiApi.getAnomalies', () => aiApi.getAnomalies()],
    ['aiApi.getPredictions', () => aiApi.getPredictions()],
    ['sensorApi.getSensors', () => sensorApi.getSensors()],
  ];

  for (const [name, call] of cases) {
    it(`${name} rejects when the gateway is unreachable`, async () => {
      get.mockImplementation(reject(undefined));
      await expect(call()).rejects.toBeInstanceOf(ApiError);
    });

    it(`${name} rejects on a server error`, async () => {
      get.mockImplementation(reject(503));
      await expect(call()).rejects.toBeInstanceOf(ApiError);
    });
  }
});

describe('single-resource endpoints separate "not there" from "could not ask"', () => {
  const cases: Array<[string, () => Promise<unknown>]> = [
    ['aiApi.getPrediction', () => aiApi.getPrediction('STN-1')],
    ['aiApi.getReport', () => aiApi.getReport('STN-1')],
    ['analyticsApi.getFleetReport', () => analyticsApi.getFleetReport('daily')],
    ['firmwareApi.getByVersion', () => firmwareApi.getByVersion('1.0.0')],
    ['sensorApi.getLatestReading', () => sensorApi.getLatestReading('STN-1')],
    ['sensorApi.getHealth', () => sensorApi.getHealth('STN-1')],
  ];

  for (const [name, call] of cases) {
    it(`${name} returns null on a 404`, async () => {
      get.mockImplementation(reject(404));
      await expect(call()).resolves.toBeNull();
    });

    it(`${name} rejects on a 500`, async () => {
      get.mockImplementation(reject(500));
      await expect(call()).rejects.toBeInstanceOf(ApiError);
    });
  }
});

describe('deviceApi.list', () => {
  const rawDevice = {
    deviceUid: 'STN-0001',
    deviceName: 'North Ridge',
    status: 'ONLINE',
    batteryLevel: 88,
  };

  it('fetches every reading in one request, not one per station', async () => {
    const devices = Array.from({ length: 50 }, (_, i) => ({ ...rawDevice, deviceUid: `STN-${i}` }));
    get.mockImplementation((url: string) => {
      if (url === '/devices') return resolve({ data: devices })();
      if (url === '/sensors/latest') return resolve({ data: {} })();
      throw new Error(`unexpected per-device request: ${url}`);
    });

    await deviceApi.list();

    // Was 1 + N: `/devices` plus `/sensors/{uid}/latest` fifty times. At 500
    // stations that was 501 round trips through the gateway for one page.
    expect(get).toHaveBeenCalledTimes(2);
    expect(get).toHaveBeenCalledWith('/sensors/latest', {
      params: { deviceUids: devices.map((d) => d.deviceUid).join(',') },
    });
  });

  it('marks a station missing from the batch as absent, not as zero degrees', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/devices') return resolve({ data: [rawDevice] })();
      // sensor-service omits devices that have never reported.
      return resolve({ data: {} })();
    });

    const [station] = await deviceApi.list();
    expect(station.reading).toBe('absent');
    expect(station.temperature).toBeNull();
    expect(station.airQuality).toBeNull();
    expect(station.rain).toBeNull();
  });

  it('marks every station unavailable when the readings call fails, and still lists them', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/devices') return resolve({ data: [rawDevice] })();
      return reject(500)();
    });

    // The stations are deployed; only their measurements are unknown. Losing
    // the readings must not empty the fleet.
    const stations = await deviceApi.list();
    expect(stations).toHaveLength(1);
    expect(stations[0].reading).toBe('unavailable');
    expect(stations[0].temperature).toBeNull();
    expect(stations[0].battery).toBe(88);
  });

  it('still fails the whole call when the device list itself cannot be read', async () => {
    get.mockImplementation(reject(503));
    await expect(deviceApi.list()).rejects.toBeInstanceOf(ApiError);
  });

  it('keeps the values of a station that did report', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/devices') return resolve({ data: [rawDevice] })();
      return resolve({
        data: { 'STN-0001': { temperature: 18.6, humidity: 61, airQuality: 22, rain: false } },
      })();
    });

    const [station] = await deviceApi.list();
    expect(station.reading).toBe('ok');
    expect(station.temperature).toBe(18.6);
    expect(station.rain).toBe(false);
  });

  it('does not call the gateway at all for an empty fleet', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/devices') return resolve({ data: [] })();
      throw new Error('should not ask for readings when there are no devices');
    });

    await expect(deviceApi.list()).resolves.toEqual([]);
    expect(get).toHaveBeenCalledTimes(1);
  });
});

describe('sensorApi.getSensors', () => {
  type Reply = () => Promise<unknown>;

  /**
   * Two stations, with the health and reading batches answering independently.
   * Either argument may be a body to return or a `reject(...)` to fail with.
   */
  const twoStations = (health: unknown | Reply, readings: unknown | Reply) => {
    const reply = (value: unknown | Reply): Reply =>
      typeof value === 'function' ? (value as Reply) : resolve({ data: value });
    return (url: string): Promise<unknown> => {
      if (url === '/devices') return resolve({ data: [{ deviceUid: 'STN-A' }, { deviceUid: 'STN-B' }] })();
      if (url === '/sensors/health') return reply(health)();
      if (url === '/sensors/latest') return reply(readings)();
      throw new Error('unexpected per-station request: ' + url);
    };
  };

  const healthyProbe = { overallStatus: 'HEALTHY', sensors: { TEMPERATURE: { status: 'HEALTHY' } } };

  it('draws the whole fleet in three requests, not one per station', async () => {
    get.mockImplementation(twoStations({ 'STN-A': healthyProbe, 'STN-B': healthyProbe }, {}));

    await sensorApi.getSensors();

    // The device list, one batched reading fetch, one batched health fetch.
    // This page used to cost 1 + 2N.
    expect(get).toHaveBeenCalledTimes(3);
    expect(get.mock.calls.map((call) => call[0]).sort()).toEqual(['/devices', '/sensors/health', '/sensors/latest']);
  });

  it('names every station when diagnostics could not be read at all', async () => {
    get.mockImplementation(twoStations(reject(500), {}));

    const inventory = await sensorApi.getSensors();

    // The batch is all-or-nothing, so a failure leaves nothing known about any
    // probe. Reporting an empty, uneventful sensor list would read as a fleet
    // with no failures.
    expect(inventory.unreadable).toEqual(['STN-A', 'STN-B']);
    expect(inventory.sensors).toEqual([]);
    expect(inventory.unevaluated).toEqual([]);
  });

  it('separates a station that was never evaluated from one that failed to answer', async () => {
    // The service answered, and its answer omits STN-A: nothing has evaluated
    // it. That is not a failure to read, and it is not a clean bill of health.
    get.mockImplementation(twoStations({ 'STN-B': healthyProbe }, {}));

    const inventory = await sensorApi.getSensors();

    expect(inventory.unevaluated).toEqual(['STN-A']);
    expect(inventory.unreadable).toEqual([]);
    expect(inventory.sensors.map((sensor) => sensor.stationUid)).toEqual(['STN-B']);
  });

  it('still lists the probes when only the readings are unavailable', async () => {
    get.mockImplementation(twoStations({ 'STN-B': healthyProbe }, reject(503)));

    const inventory = await sensorApi.getSensors();

    // Diagnostics are worth showing without current values; the value goes
    // unknown rather than the row disappearing.
    expect(inventory.sensors).toHaveLength(1);
    expect(inventory.sensors[0].value).toBeNull();
  });

  it('gives a probe with no current reading a null value rather than zero', async () => {
    get.mockImplementation(twoStations(
      { 'STN-B': { overallStatus: 'WARNING', sensors: { TEMPERATURE: { status: 'WARNING' } } } },
      {},
    ));

    const inventory = await sensorApi.getSensors();

    expect(inventory.sensors[0].value).toBeNull();
    expect(inventory.sensors[0].health).toBe('degraded');
  });

  it('reads a probe status it does not recognise as unknown, never as healthy', async () => {
    get.mockImplementation(twoStations(
      { 'STN-B': { overallStatus: 'HEALTHY', sensors: { TEMPERATURE: { status: 'RECALIBRATING' } } } },
      {},
    ));

    const inventory = await sensorApi.getSensors();

    expect(inventory.sensors[0].health).toBe('unknown');
  });

  it('does not ask for readings or diagnostics for an empty fleet', async () => {
    get.mockImplementation((url: string) => {
      if (url === '/devices') return resolve({ data: [] })();
      throw new Error('should not ask about stations that do not exist');
    });

    await expect(sensorApi.getSensors()).resolves.toEqual({ sensors: [], unreadable: [], unevaluated: [] });
    expect(get).toHaveBeenCalledTimes(1);
  });
});
