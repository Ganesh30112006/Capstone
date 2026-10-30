import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { Thermometer } from 'lucide-react';
import { Metric } from './Metric';
import { UNKNOWN } from '@/lib/format';

describe('Metric', () => {
  it('shows the unit alongside a real figure', () => {
    render(<Metric icon={Thermometer} label="Temperature" value="18.6" unit="°C" />);
    expect(screen.getByTestId('metric-temperature')).toHaveTextContent('18.6°C');
  });

  it('drops the unit when the figure is unknown', () => {
    // "— °C" reads as a temperature. An unknown value has no unit to carry.
    render(<Metric icon={Thermometer} label="Temperature" value={UNKNOWN} unit="°C" />);
    const tile = screen.getByTestId('metric-temperature');
    expect(tile).toHaveTextContent(UNKNOWN);
    expect(tile).not.toHaveTextContent('°C');
  });
});
