import { Suspense, lazy } from 'react';

// Recharts is the single largest dependency in the bundle; pages that never
// draw a chart should not pay for it.
const EnvironmentalChartView = lazy(() => import('./EnvironmentalChartView'));

export function EnvironmentalChart({ compact = false, source = 'operator' }: { compact?: boolean; source?: 'operator' | 'public' }) {
  return (
    <Suspense fallback={<div className={`chart-wrap ${compact ? 'compact' : ''}`} aria-busy="true" />}>
      <EnvironmentalChartView compact={compact} source={source} />
    </Suspense>
  );
}
