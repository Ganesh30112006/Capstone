import { describe, it, expect, vi, beforeEach } from 'vitest';
import type { ReactElement } from 'react';
import { render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';

import { deviceApi } from '@/api/deviceApi';
import { alertApi } from '@/api/alertApi';
import { sensorApi } from '@/api/sensorApi';
import { userApi } from '@/api/userApi';
import { authApi } from '@/api/authApi';
import { analyticsApi } from '@/api/analyticsApi';
import { aiApi } from '@/api/aiApi';
import { firmwareApi } from '@/api/firmwareApi';
import { publicApi } from '@/api/publicApi';

import { Devices } from './admin/Devices';
import { Alerts } from './admin/Alerts';
import { Sensors } from './admin/Sensors';
import { Users } from './admin/Users';
import { AdminOverview } from './admin/AdminOverview';
import { Firmware } from './admin/Firmware';
import { StationDirectory } from './public/StationDirectory';
import { Home } from './public/Home';
import { Weather } from './public/Weather';

/**
 * A page-level counterpart to the api/* tests: proving the distinction
 * survives all the way to the screen.
 *
 * Each case drives the same page twice — once with the backend answering
 * "nothing", once with it not answering at all — and asserts the two look
 * different. Before this work they were pixel-identical, because the api layer
 * turned every failure into an empty array on the way up.
 */

vi.mock('@/api/deviceApi', () => ({ deviceApi: { list: vi.fn(), get: vi.fn(), getDigitalTwin: vi.fn(), getSupportedCommands: vi.fn(), sendCommand: vi.fn() } }));
vi.mock('@/api/alertApi', () => ({ alertApi: { list: vi.fn(), acknowledge: vi.fn(), resolve: vi.fn() } }));
vi.mock('@/api/sensorApi', () => ({ sensorApi: { getSensors: vi.fn(), getHealth: vi.fn(), getLatestReading: vi.fn() } }));
vi.mock('@/api/userApi', () => ({ userApi: { list: vi.fn(), updateRole: vi.fn(), remove: vi.fn() } }));
vi.mock('@/api/authApi', () => ({ authApi: { me: vi.fn(), login: vi.fn(), logout: vi.fn() } }));
vi.mock('@/api/analyticsApi', () => ({ analyticsApi: { getNetworkTrend: vi.fn(), getHeatmap: vi.fn(), getDeviceReport: vi.fn(), getFleetReport: vi.fn() } }));
vi.mock('@/api/aiApi', () => ({ aiApi: { getAnomalies: vi.fn(), getForecast: vi.fn(), getPredictions: vi.fn(), getPrediction: vi.fn(), getReport: vi.fn(), generateReport: vi.fn(), getModelInfo: vi.fn() } }));
vi.mock('@/api/publicApi', () => ({ PUBLIC_REFRESH_MS: 5000, publicApi: { listStations: vi.fn(), getStation: vi.fn(), getTrend: vi.fn(), getForecast: vi.fn() } }));
vi.mock('@/api/firmwareApi', () => ({ firmwareApi: { list: vi.fn(), getByVersion: vi.fn(), register: vi.fn(), requestUpdate: vi.fn() } }));
vi.mock('@/hooks/use-live-stream', () => ({
  useLiveStream: () => ({ status: 'closed', telemetry: {}, heartbeats: {}, predictions: {}, reports: {}, anomalies: [] }),
}));
// Charts pull in Recharts lazily and have nothing to say about data states.
vi.mock('@/components/EnvironmentalChart', () => ({ EnvironmentalChart: () => <div data-testid="chart-stub" /> }));

function renderPage(ui: ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>{ui}</MemoryRouter>
    </QueryClientProvider>,
  );
}

const outage = () => Promise.reject(new Error('Could not reach the API Gateway.'));

beforeEach(() => {
  vi.clearAllMocks();
  // Sensible "answered, and the answer is nothing" defaults; each test
  // overrides only the query it is about.
  vi.mocked(deviceApi.list).mockResolvedValue([]);
  vi.mocked(alertApi.list).mockResolvedValue([]);
  vi.mocked(sensorApi.getSensors).mockResolvedValue({ sensors: [], unreadable: [], unevaluated: [] });
  vi.mocked(userApi.list).mockResolvedValue([]);
  vi.mocked(analyticsApi.getNetworkTrend).mockResolvedValue([]);
  vi.mocked(analyticsApi.getFleetReport).mockResolvedValue(null);
  vi.mocked(aiApi.getAnomalies).mockResolvedValue([]);
  vi.mocked(aiApi.getForecast).mockResolvedValue({ success: true, data: { predictions: [] } });
  vi.mocked(firmwareApi.list).mockResolvedValue([]);
  vi.mocked(publicApi.listStations).mockResolvedValue([]);
  vi.mocked(publicApi.getTrend).mockResolvedValue([]);
  vi.mocked(publicApi.getForecast).mockResolvedValue(null);
  vi.mocked(authApi.me).mockResolvedValue({ success: true, data: { id: 1, name: 'Avery Stone', email: 'a@example.com', role: 'ADMIN' } });
});

describe('an unreachable backend is never drawn as an empty one', () => {
  const cases: Array<{ name: string; page: () => ReactElement; fail: () => void; emptyText: RegExp }> = [
    {
      name: 'Devices',
      page: () => <Devices />,
      fail: () => vi.mocked(deviceApi.list).mockImplementation(outage),
      emptyText: /No devices registered/i,
    },
    {
      name: 'Alerts',
      page: () => <Alerts />,
      fail: () => vi.mocked(alertApi.list).mockImplementation(outage),
      emptyText: /Nothing in this queue/i,
    },
    {
      name: 'Sensors',
      page: () => <Sensors />,
      fail: () => vi.mocked(sensorApi.getSensors).mockImplementation(outage),
      emptyText: /No sensors have reported diagnostics/i,
    },
    {
      name: 'Users',
      page: () => <Users />,
      fail: () => vi.mocked(userApi.list).mockImplementation(outage),
      emptyText: /No accounts are registered/i,
    },
    {
      name: 'Firmware',
      page: () => <Firmware />,
      fail: () => vi.mocked(firmwareApi.list).mockImplementation(outage),
      emptyText: /No firmware registered/i,
    },
    {
      name: 'StationDirectory',
      page: () => <StationDirectory />,
      fail: () => vi.mocked(publicApi.listStations).mockImplementation(outage),
      emptyText: /No stations registered/i,
    },
    {
      name: 'Home',
      page: () => <Home />,
      fail: () => vi.mocked(publicApi.listStations).mockImplementation(outage),
      emptyText: /No stations are reporting to this network yet/i,
    },
  ];

  for (const { name, page, fail, emptyText } of cases) {
    it(`${name} shows an empty state when the backend answers with nothing`, async () => {
      renderPage(page());
      await waitFor(() => expect(screen.getByText(emptyText)).toBeInTheDocument());
      expect(screen.queryByTestId('notice-unavailable')).not.toBeInTheDocument();
    });

    it(`${name} shows an unavailable notice — not the empty state — when the backend does not answer`, async () => {
      fail();
      renderPage(page());
      await waitFor(() => expect(screen.getAllByTestId('notice-unavailable').length).toBeGreaterThan(0));
      expect(screen.queryByText(emptyText)).not.toBeInTheDocument();
    });
  }
});

describe('derived figures do not assert a number the console could not read', () => {
  it('AdminOverview reports station and alert counts as unknown when both queries fail', async () => {
    vi.mocked(deviceApi.list).mockImplementation(outage);
    vi.mocked(alertApi.list).mockImplementation(outage);

    renderPage(<AdminOverview />);

    await waitFor(() => expect(screen.getAllByTestId('notice-unavailable').length).toBe(2));
    // "0 active alerts" beside a banner saying the queue could not be read is
    // a contradiction; the tile goes unknown instead.
    expect(screen.getByTestId('metric-active-alerts')).toHaveTextContent('—');
    expect(screen.getByTestId('metric-reporting-stations')).toHaveTextContent('—');
  });

  it('AdminOverview still reports real counts when the queries answer', async () => {
    vi.mocked(alertApi.list).mockResolvedValue([
      { id: '1', stationUid: 'STN-1', title: 'High AQI', parameter: 'airQuality', severity: 'critical', status: 'active', timestamp: 'now', summary: '' },
    ]);

    renderPage(<AdminOverview />);

    await waitFor(() => expect(screen.getByTestId('metric-active-alerts')).toHaveTextContent('1'));
    expect(screen.queryByTestId('notice-unavailable')).not.toBeInTheDocument();
  });

  it('Sensors names the stations whose diagnostics could not be read', async () => {
    vi.mocked(sensorApi.getSensors).mockResolvedValue({
      sensors: [{ name: 'Temperature', stationUid: 'STN-B', value: 20, unit: '°C', health: 'healthy', lastReading: 'now' }],
      unreadable: ['STN-A'],
      unevaluated: [],
    });

    renderPage(<Sensors />);

    await waitFor(() => expect(screen.getByTestId('notice-unavailable')).toBeInTheDocument());
    // The station is named rather than silently missing from a healthy-looking
    // list of one.
    expect(screen.getByText(/STN-A did not answer their health probe/i)).toBeInTheDocument();
  });

  it('Sensors says when a station has never been evaluated, rather than implying it is clean', async () => {
    vi.mocked(sensorApi.getSensors).mockResolvedValue({
      sensors: [{ name: 'Temperature', stationUid: 'STN-B', value: 20, unit: '°C', health: 'healthy', lastReading: 'now' }],
      unreadable: [],
      unevaluated: ['STN-A'],
    });

    renderPage(<Sensors />);

    await waitFor(() => expect(screen.getByTestId('notice-unevaluated-stations')).toBeInTheDocument());
    expect(screen.getByText(/never been evaluated/i)).toBeInTheDocument();
    // This is a different statement from a failed read, so it must not borrow
    // that banner.
    expect(screen.queryByTestId('notice-unavailable')).not.toBeInTheDocument();
  });

  it('Sensors shows an unrecognised probe status as unknown, not as a passed check', async () => {
    vi.mocked(sensorApi.getSensors).mockResolvedValue({
      sensors: [{ name: 'Temperature', stationUid: 'STN-B', value: null, unit: '°C', health: 'unknown', lastReading: 'Unknown' }],
      unreadable: [],
      unevaluated: [],
    });

    renderPage(<Sensors />);

    const row = await screen.findByTestId('row-sensor-STN-B-Temperature');
    expect(within(row).getByText('unknown')).toBeInTheDocument();
    // The healthy tile counts passed checks only.
    expect(screen.getByTestId('metric-healthy-sensors')).toHaveTextContent('0');
  });
});

describe('measurements are not invented', () => {
  const stationWithoutReading = {
    uid: 'STN-0001',
    name: 'North Ridge',
    location: 'Ridge · 1240 m',
    status: 'online' as const,
    lastSeen: 'just now',
    readingAt: null,
    latitude: null,
    longitude: null,
    temperature: null,
    humidity: null,
    airQuality: null,
    oxygen: null,
    pressure: null,
    rain: null,
    battery: null,
    firmware: null,
    wifiSignal: null,
    // No coordinates from either source, so nothing to plot: the station
    // is absent from the map rather than drawn at the centre of the view.
    position: null,
    positionDriftMetres: null,
    reading: 'absent' as const,
  };

  it('a station that has never reported shows a dash, not 0.0 degrees', async () => {
    vi.mocked(publicApi.listStations).mockResolvedValue([stationWithoutReading]);

    renderPage(<StationDirectory />);

    await waitFor(() => expect(screen.getByTestId('card-directory-STN-0001')).toBeInTheDocument());
    const card = screen.getByTestId('card-directory-STN-0001');
    expect(card).toHaveTextContent('—');
    expect(card).not.toHaveTextContent('0.0°');
    // The battery cell used to render the literal string "null%".
    expect(card).not.toHaveTextContent('null%');
  });

  it('Weather shows no forecast rather than a fabricated one', async () => {
    vi.mocked(publicApi.listStations).mockResolvedValue([stationWithoutReading]);
    vi.mocked(publicApi.getForecast).mockResolvedValue(null);

    renderPage(<Weather />);

    await waitFor(() => expect(screen.getByTestId('forecast-empty')).toBeInTheDocument());
    // The page used to fall back to a hardcoded four-day forecast under a
    // heading crediting the AI forecaster.
    expect(screen.queryByText(/Low rain/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/Rain likely/i)).not.toBeInTheDocument();
  });

  it('Weather reports a forecast failure rather than falling back to invented days', async () => {
    vi.mocked(publicApi.listStations).mockResolvedValue([stationWithoutReading]);
    vi.mocked(publicApi.getForecast).mockImplementation(outage);

    renderPage(<Weather />);

    await waitFor(() => expect(screen.getByTestId('notice-unavailable')).toBeInTheDocument());
    expect(screen.queryByTestId('forecast-empty')).not.toBeInTheDocument();
  });
});
