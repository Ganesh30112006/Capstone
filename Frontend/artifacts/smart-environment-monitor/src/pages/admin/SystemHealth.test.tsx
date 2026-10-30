import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { SystemHealth } from './SystemHealth';
import { systemApi } from '@/api/systemApi';
import { streamApi } from '@/api/streamApi';

vi.mock('@/api/systemApi', () => ({
  systemApi: { getServiceHealth: vi.fn() },
}));

vi.mock('@/api/streamApi', () => ({
  streamApi: { getStatus: vi.fn(() => Promise.resolve(null)) },
}));

/**
 * This page exists to say when something is broken, which makes it the one
 * page where a false negative is worse than no page at all.
 *
 * The failure it had: `systemApi.getServiceHealth` caught every error and
 * returned `[]`. An empty list has no outages and no degradations in it, so
 * the summary fell through to its last branch and rendered a green "All
 * services operational — every probed service reported healthy on its last
 * check". A gateway that could not be reached at all therefore looked exactly
 * like a fleet in perfect health.
 */
function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <SystemHealth />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

describe('SystemHealth', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(streamApi.getStatus).mockResolvedValue(null as never);
  });

  it('does not claim everything is healthy when the probe could not run', async () => {
    vi.mocked(systemApi.getServiceHealth).mockRejectedValue(new Error('gateway unreachable'));

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/could not be reached/i)).toBeInTheDocument();
    });
    expect(screen.queryByText(/All services operational/i)).not.toBeInTheDocument();
  });

  it('reports outages when the probe does run', async () => {
    vi.mocked(systemApi.getServiceHealth).mockResolvedValue([
      { name: 'Alerting', status: 'outage', version: '—', lastCheck: '12 ms' },
      { name: 'Analytics', status: 'operational', version: '—', lastCheck: '8 ms' },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/1 service unreachable/i)).toBeInTheDocument();
    });
  });

  it('still reports all-clear when the probe genuinely returns healthy services', async () => {
    vi.mocked(systemApi.getServiceHealth).mockResolvedValue([
      { name: 'Alerting', status: 'operational', version: '—', lastCheck: '9 ms' },
    ]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/All services operational/i)).toBeInTheDocument();
    });
  });

  it('distinguishes an empty but successful probe from a failed one', async () => {
    // The gateway answered, and had nothing to report. That is not an outage,
    // but it is not a clean bill of health either — nothing was checked.
    vi.mocked(systemApi.getServiceHealth).mockResolvedValue([]);

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/No services were probed/i)).toBeInTheDocument();
    });
    expect(screen.queryByText(/All services operational/i)).not.toBeInTheDocument();
  });

  it('does not claim a probe count when the probe never ran', async () => {
    vi.mocked(systemApi.getServiceHealth).mockRejectedValue(new Error('gateway unreachable'));

    renderPage();

    await waitFor(() => {
      expect(screen.getByText(/could not be reached/i)).toBeInTheDocument();
    });
    // "0 probed" beside "could not be reached" states a count for a check that
    // never happened.
    expect(screen.queryByText(/0 probed/i)).not.toBeInTheDocument();
    expect(screen.getByText(/— probed/)).toBeInTheDocument();
  });

  it('does not present the loading state as a verdict', () => {
    vi.mocked(systemApi.getServiceHealth).mockReturnValue(new Promise(() => {}));

    renderPage();

    expect(screen.getByTestId('system-summary-title')).toHaveTextContent(/Checking service health/i);
    expect(screen.queryByText(/All services operational/i)).not.toBeInTheDocument();
  });
});
