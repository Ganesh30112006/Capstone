import { useQuery } from '@tanstack/react-query';
import { PUBLIC_REFRESH_MS, publicApi, type ForecastMetric } from '@/api/publicApi';

/*
 * The public pages' queries, in one place so every page and the header share
 * one cache entry -- the header's "Updated 3s ago" describes the same read the
 * page is showing. Re-reads pause while the tab is hidden (React Query's
 * default), so a background tab costs nothing.
 */

export const publicKeys = {
  stations: ['public', 'stations'] as const,
  station: (uid: string) => ['public', 'station', uid] as const,
  trend: ['public', 'trend'] as const,
  forecast: (uid: string) => ['public', 'forecast', uid] as const,
  skill: ['public', 'forecast-skill'] as const,
  receipts: (uid: string, metric: string) => ['public', 'forecast-receipts', uid, metric] as const,
};

export function usePublicStations() {
  return useQuery({
    queryKey: publicKeys.stations,
    queryFn: publicApi.listStations,
    refetchInterval: PUBLIC_REFRESH_MS,
  });
}

export function usePublicStation(uid: string) {
  return useQuery({
    queryKey: publicKeys.station(uid),
    queryFn: () => publicApi.getStation(uid),
    enabled: !!uid,
    refetchInterval: PUBLIC_REFRESH_MS,
  });
}

/** Daily averages change once a day; re-reading them every few seconds would be waste. */
export function usePublicTrend() {
  return useQuery({ queryKey: publicKeys.trend, queryFn: publicApi.getTrend, staleTime: 5 * 60_000 });
}

export function usePublicForecast(uid: string) {
  return useQuery({
    queryKey: publicKeys.forecast(uid),
    queryFn: () => publicApi.getForecast(uid),
    enabled: !!uid,
    refetchInterval: PUBLIC_REFRESH_MS * 2,
  });
}

/** Aggregates move slowly; every few refreshes is plenty. */
export function usePublicForecastSkill() {
  return useQuery({ queryKey: publicKeys.skill, queryFn: publicApi.getForecastSkill, refetchInterval: PUBLIC_REFRESH_MS * 2 });
}

export function usePublicForecastReceipts(uid: string, metric: ForecastMetric) {
  return useQuery({
    queryKey: publicKeys.receipts(uid, metric),
    queryFn: () => publicApi.getForecastReceipts(uid, metric),
    enabled: !!uid,
    refetchInterval: PUBLIC_REFRESH_MS,
  });
}
