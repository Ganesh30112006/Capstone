import { MapPinned } from 'lucide-react';
import type { Station } from '@/types/domain';

/** A labelled native select: keyboard, screen-reader and mobile pickers all work as the platform intends. */
export function StationPicker({ stations, value, onChange, testId, label }: {
  stations: Station[];
  value: string;
  onChange: (uid: string) => void;
  testId: string;
  label: string;
}) {
  return (
    <label className="station-picker">
      <MapPinned size={18} aria-hidden="true" />
      <span className="visually-hidden">{label}</span>
      <select value={value} onChange={(e) => onChange(e.target.value)} className="station-select" data-testid={testId}>
        {stations.map((s) => <option key={s.uid} value={s.uid}>{s.name} · {s.location}</option>)}
      </select>
    </label>
  );
}
