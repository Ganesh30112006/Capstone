import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import App from './App';
import { authApi } from './api/authApi';
import { deviceApi } from './api/deviceApi';
import { publicApi } from './api/publicApi';

vi.mock('./api/authApi', () => ({
  authApi: {
    me: vi.fn(),
    login: vi.fn(),
    logout: vi.fn(),
  },
}));

vi.mock('./api/deviceApi', () => ({
  deviceApi: {
    list: vi.fn(() => Promise.resolve([])),
    get: vi.fn(() => Promise.resolve(null)),
    getDigitalTwin: vi.fn(() => Promise.resolve(null)),
    getSupportedCommands: vi.fn(() => Promise.resolve([])),
    sendCommand: vi.fn(),
  },
}));

vi.mock('./api/publicApi', () => ({
  PUBLIC_REFRESH_MS: 5000,
  publicApi: {
    listStations: vi.fn(() => Promise.resolve([])),
    getStation: vi.fn(() => Promise.reject(new Error('not found'))),
    getTrend: vi.fn(() => Promise.resolve([])),
    getForecast: vi.fn(() => Promise.resolve(null)),
  },
}));

vi.mock('./api/alertApi', () => ({
  alertApi: { list: vi.fn(() => Promise.resolve([])), acknowledge: vi.fn(), resolve: vi.fn() },
}));

vi.mock('./api/analyticsApi', () => ({
  analyticsApi: {
    getNetworkTrend: vi.fn(() => Promise.resolve([])),
    getHeatmap: vi.fn(() => Promise.resolve({ points: [], parameter: 'temperature', timestamp: '' })),
    getDeviceReport: vi.fn(() => Promise.resolve(null)),
    getFleetReport: vi.fn(() => Promise.resolve(null)),
  },
}));

// The admin shell opens a live WebSocket; keep it inert under test.
vi.mock('./hooks/use-live-stream', () => ({
  useLiveStream: () => ({ status: 'closed', telemetry: {}, heartbeats: {}, predictions: {}, reports: {}, anomalies: [] }),
}));

describe('Smart Environment Monitor security boundaries', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    localStorage.clear();
    window.history.pushState({}, 'Test page', '/');
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  const renderWithRoute = (route: string) => {
    window.history.pushState({}, 'Test page', route);
    return render(<App />);
  };

  describe('public routes', () => {
    it('allows access to public pages without authentication', async () => {
      renderWithRoute('/');
      expect(screen.getByText(/Live air and weather/i)).toBeInTheDocument();
      expect(authApi.me).not.toHaveBeenCalled();
    });

    it('renders the station directory without a session', async () => {
      renderWithRoute('/stations');
      await waitFor(() => {
        expect(screen.getByText(/The stations in the field/i)).toBeInTheDocument();
      });
      // Public pages read the anonymous API. They used to read the operator
      // one, and showed nothing to anyone without a valid operator token.
      expect(publicApi.listStations).toHaveBeenCalled();
      expect(deviceApi.list).not.toHaveBeenCalled();
    });

    it('renders 404 for unknown routes', () => {
      renderWithRoute('/unknown-invalid-route');
      expect(screen.getByText(/That signal is not here/i)).toBeInTheDocument();
    });
  });

  describe('admin authorization', () => {
    it('redirects unauthenticated users to the login page', async () => {
      vi.mocked(authApi.me).mockRejectedValue(new Error('Not authenticated'));
      renderWithRoute('/admin/dashboard');

      await waitFor(() => {
        expect(screen.getByText(/RESTRICTED ACCESS/i)).toBeInTheDocument();
        expect(screen.getByLabelText(/Email address/i)).toBeInTheDocument();
      });
    });

    it('redirects an authenticated USER to 403 rather than rendering the console', async () => {
      vi.mocked(authApi.me).mockResolvedValue({
        success: true,
        data: { id: 2, name: 'Field User', email: 'user@example.com', role: 'USER' },
      });

      renderWithRoute('/admin/dashboard');

      await waitFor(() => {
        expect(screen.getByText(/This reading is restricted/i)).toBeInTheDocument();
      });
    });

    it('allows an ADMIN into the dashboard and greets them by name', async () => {
      // Fixed clock so the time-of-day greeting is deterministic.
      vi.useFakeTimers({ shouldAdvanceTime: true });
      vi.setSystemTime(new Date('2026-05-14T14:30:00'));

      vi.mocked(authApi.me).mockResolvedValue({
        success: true,
        data: { id: 1, name: 'Avery Stone', email: 'admin@example.com', role: 'ADMIN' },
      });

      renderWithRoute('/admin/dashboard');

      await waitFor(() => {
        expect(screen.getByText(/Good afternoon, Avery\./i)).toBeInTheDocument();
      });
      // Hand the clock back before this test ends rather than in afterEach.
      // React unmounts during cleanup, and anything it had scheduled under the
      // fake clock was still pending when the next test started — which is the
      // most likely source of the intermittent failure seen in the test below.
      vi.useRealTimers();
      // Raised from the 5s default: this renders the whole admin shell — router,
      // query client, charts — and on a cold run it was measured at 8.4s and
      // failed, while passing in 0.6s once warm. A timeout that only holds on a
      // warm machine reports the cache, not the code.
    }, 20_000);

    it('routes the hardware console to its authorized path only', async () => {
      vi.mocked(authApi.me).mockResolvedValue({
        success: true,
        data: { id: 1, name: 'Avery Stone', email: 'admin@example.com', role: 'ADMIN' },
      });

      renderWithRoute('/admin/hardware-debug');

      // Same cold-start cost as the query-client/charts case above: this
      // route lazy-loads HardwareDebug plus its own chunk of the admin bundle,
      // and the default waitFor timeout only holds once that chunk is already
      // warm in the module cache. Seen failing at ~1.3s under concurrent
      // system load with no code change involved.
      await waitFor(() => {
        expect(screen.getByText(/Secure hardware link/i)).toBeInTheDocument();
      }, { timeout: 20_000 });
      // No free-text command field: the console offers the closed command set only.
      expect(screen.getByTestId('button-command-PING')).toBeInTheDocument();
      expect(screen.getByTestId('button-command-OTA_CHECK')).toBeInTheDocument();
      expect(screen.queryByPlaceholderText(/command/i)).not.toBeInTheDocument();
      // Same reason as the dashboard test above: this mounts the whole admin
      // shell, and on a cold run under the full suite it has been seen to
      // exceed the 5s default while completing in ~1s warm.
    }, 20_000);
  });

  describe('authentication flow', () => {
    it('reports a failed login without leaving the form', async () => {
      vi.mocked(authApi.login).mockResolvedValue({ success: false, message: 'Invalid credentials' });
      renderWithRoute('/admin/login');

      // By label, so the test fails if a label stops being tied to its field.
      fireEvent.change(screen.getByLabelText(/Email address/i), { target: { value: 'bad@example.com' } });
      fireEvent.change(screen.getByLabelText(/Password/i), { target: { value: 'wrong' } });
      fireEvent.click(screen.getByText(/Sign in safely/i));

      await waitFor(() => {
        expect(authApi.login).toHaveBeenCalledWith({ email: 'bad@example.com', password: 'wrong' });
        expect(screen.getByText(/Invalid credentials/i)).toBeInTheDocument();
      });
      expect(localStorage.getItem('auth-token')).toBeNull();
    });

    it('stores the token and enters the console on a successful login', async () => {
      vi.mocked(authApi.login).mockResolvedValue({ success: true, data: { token: 'test-token' } });
      vi.mocked(authApi.me).mockResolvedValue({
        success: true,
        data: { id: 1, name: 'Avery Stone', email: 'admin@example.com', role: 'ADMIN' },
      });
      renderWithRoute('/admin/login');

      fireEvent.change(screen.getByLabelText(/Email address/i), { target: { value: 'admin@example.com' } });
      fireEvent.change(screen.getByLabelText(/Password/i), { target: { value: 'correct-horse' } });
      fireEvent.click(screen.getByText(/Sign in safely/i));

      await waitFor(() => {
        expect(localStorage.getItem('auth-token')).toBe('test-token');
      });
    });
  });
});
