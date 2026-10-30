import { describe, expect, it } from 'vitest';
import { UNKNOWN, signalQuality } from './format';

describe('Wi-Fi signal', () => {
  it('is shown in the dBm the station reports, not as a percentage', () => {
    expect(signalQuality(-38)).toBe('-38 dBm · excellent');
  });

  it('bands the strength by the usual RSSI thresholds', () => {
    expect(signalQuality(-50)).toBe('-50 dBm · excellent');
    expect(signalQuality(-51)).toBe('-51 dBm · good');
    expect(signalQuality(-60)).toBe('-60 dBm · good');
    expect(signalQuality(-70)).toBe('-70 dBm · fair');
    expect(signalQuality(-71)).toBe('-71 dBm · weak');
  });

  it('has no strength for a station that has not reported one', () => {
    expect(signalQuality(null)).toBe(UNKNOWN);
    expect(signalQuality(undefined)).toBe(UNKNOWN);
  });
});
