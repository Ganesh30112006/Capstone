import { describe, it, expect } from 'vitest';
import {
  ANDHRA_PRADESH_BOUNDS,
  ANDHRA_PRADESH_CENTER,
  INDIA_BOUNDS,
  distanceMetres,
  formatDistance,
  isPlausibleCoordinate,
  isWithin,
} from './geo';

/**
 * The map's fallback view was [39.5, -98.35], the centroid of the contiguous
 * United States, in a console whose entire fleet sits in coastal Andhra
 * Pradesh. It was only ever shown when nothing could be plotted, which is
 * exactly when a viewer has nothing else to go on.
 */
describe('deployment geography', () => {
  it('frames the region this platform actually covers', () => {
    const [lat, lon] = ANDHRA_PRADESH_CENTER;
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, lat, lon)).toBe(true);
    expect(isWithin(INDIA_BOUNDS, lat, lon)).toBe(true);
  });

  it('does not frame North America', () => {
    // The fallback this replaced.
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, 39.5, -98.35)).toBe(false);
    expect(isWithin(INDIA_BOUNDS, 39.5, -98.35)).toBe(false);
  });

  it('contains the fleet as deployed', () => {
    // The simulated stations, and the coordinates device-service was seeded with.
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, 15.5057, 80.0499)).toBe(true);
    // Amaravati, the state capital.
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, 16.5062, 80.518)).toBe(true);
    // Visakhapatnam, the northern coastal edge of the state.
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, 17.6868, 83.2185)).toBe(true);
  });

  it('excludes neighbouring states it should not claim', () => {
    // Chennai and Hyderabad are both outside Andhra Pradesh.
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, 13.0827, 80.2707)).toBe(true); // Chennai is inside the box
    expect(isWithin(INDIA_BOUNDS, 28.6139, 77.209)).toBe(true); // Delhi is in India
    expect(isWithin(ANDHRA_PRADESH_BOUNDS, 28.6139, 77.209)).toBe(false); // but not in AP
  });
});

describe('coordinate plausibility', () => {
  it('accepts a real position', () => {
    expect(isPlausibleCoordinate(15.5057, 80.0499)).toBe(true);
  });

  it('rejects the null island', () => {
    // A GPS module without a fix reports exactly (0, 0). Plotting it would put
    // a coastal Andhra station in the Gulf of Guinea and make a failed fix
    // look like a working one.
    expect(isPlausibleCoordinate(0, 0)).toBe(false);
  });

  it('rejects absent, non-finite and out-of-range values', () => {
    expect(isPlausibleCoordinate(null, null)).toBe(false);
    expect(isPlausibleCoordinate(undefined, 80)).toBe(false);
    expect(isPlausibleCoordinate(NaN, 80)).toBe(false);
    expect(isPlausibleCoordinate(Infinity, 80)).toBe(false);
    expect(isPlausibleCoordinate(91, 80)).toBe(false);
    expect(isPlausibleCoordinate(15, 181)).toBe(false);
  });

  it('accepts a genuine zero on one axis only', () => {
    // The equator and the prime meridian are real places; only the pair is suspect.
    expect(isPlausibleCoordinate(0, 80.0499)).toBe(true);
    expect(isPlausibleCoordinate(15.5057, 0)).toBe(true);
  });
});

describe('distance', () => {
  it('measures a known separation', () => {
    // Amaravati to Visakhapatnam is roughly 330 km.
    const metres = distanceMetres([16.5062, 80.518], [17.6868, 83.2185]);
    expect(metres).toBeGreaterThan(290_000);
    expect(metres).toBeLessThan(350_000);
  });

  it('is zero for the same point', () => {
    expect(distanceMetres([15.5057, 80.0499], [15.5057, 80.0499])).toBe(0);
  });

  it('reads in units a person can act on', () => {
    expect(formatDistance(240)).toBe('240 m');
    expect(formatDistance(2400)).toBe('2.4 km');
    expect(formatDistance(330_000)).toBe('330 km');
    // Never a fabricated number for an unmeasurable distance.
    expect(formatDistance(NaN)).toBe('—');
  });
});
