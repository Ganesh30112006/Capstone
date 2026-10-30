import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import EnvironmentalChartView from './EnvironmentalChartView';
import { analyticsApi } from '@/api/analyticsApi';

vi.mock('@/api/analyticsApi', () => ({
  analyticsApi: { getNetworkTrend: vi.fn() },
}));

/**
 * This chart is embedded on the dashboard, the live monitor, both station
 * pages and the device record, always unguarded. Defaulting its data to `[]`
 * meant a failed trend request drew a complete chart — grid, axes, no line —
 * which is how a missing series comes to look like a measured flat signal.
 */
function renderChart() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <EnvironmentalChartView />
    </QueryClientProvider>,
  );
}

beforeEach(() => vi.clearAllMocks());

describe('EnvironmentalChartView', () => {
  it('says the series could not be loaded rather than plotting nothing', async () => {
    vi.mocked(analyticsApi.getNetworkTrend).mockRejectedValue(new Error('Could not reach the API Gateway.'));

    renderChart();

    await waitFor(() => expect(screen.getByTestId('chart-unavailable')).toBeInTheDocument());
    expect(screen.getByText(/not a flat signal/i)).toBeInTheDocument();
    expect(screen.queryByTestId('chart-environmental-trends')).not.toBeInTheDocument();
  });

  it('distinguishes an empty window from a failed request', async () => {
    vi.mocked(analyticsApi.getNetworkTrend).mockResolvedValue([]);

    renderChart();

    await waitFor(() => expect(screen.getByTestId('chart-empty')).toBeInTheDocument());
    expect(screen.queryByTestId('chart-unavailable')).not.toBeInTheDocument();
  });

  it('plots the series when there is one', async () => {
    vi.mocked(analyticsApi.getNetworkTrend).mockResolvedValue([
      { timestamp: '08:00', temperature: 18.6, humidity: 61, airQuality: 22, oxygen: 20.9, pressure: 1013 },
    ]);

    renderChart();

    await waitFor(() => expect(screen.getByTestId('chart-environmental-trends')).toBeInTheDocument());
  });
});
