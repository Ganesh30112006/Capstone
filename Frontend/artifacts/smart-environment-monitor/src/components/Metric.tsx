import type { ComponentType } from 'react';
import type { LucideProps } from 'lucide-react';
import { UNKNOWN } from '@/lib/format';

export type IconType = ComponentType<LucideProps>;

export function Metric({
  icon: Icon,
  label,
  value,
  unit,
  tone = 'neutral',
  foot,
}: {
  icon: IconType;
  label: string;
  value: string | number;
  unit?: string;
  tone?: string;
  foot?: string;
}) {
  return (
    <div className="metric-card" data-testid={`metric-${label.toLowerCase().replaceAll(' ', '-')}`}>
      <div className={`metric-icon ${tone}`}>
        <Icon size={16} strokeWidth={1.8} />
      </div>
      <div className="metric-label">{label}</div>
      <div className="metric-value">
        {value}
        {/* A unit on an unknown value implies there is a value: "— °C"
            reads as a temperature. When the figure is unknown the unit goes
            with it. */}
        {unit && value !== UNKNOWN && <small>{unit}</small>}
      </div>
      {foot && <div className="metric-foot">{foot}</div>}
    </div>
  );
}
