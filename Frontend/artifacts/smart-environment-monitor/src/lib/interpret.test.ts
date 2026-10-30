import { airQuality, feelsLike, humidity, rain, summary } from './interpret';

describe('interpret', () => {
  it('gives no guidance for an unknown reading', () => {
    expect(airQuality(null)).toBeNull();
    expect(airQuality(Number.NaN)).toBeNull();
    expect(humidity(undefined)).toBeNull();
    expect(rain(null)).toBeNull();
    expect(feelsLike(null, 60)).toBeNull();
    expect(summary({ temperature: null, humidity: null, airQuality: null, rain: null })).toBeNull();
  });

  it('places the index on the AQI category edges', () => {
    expect(airQuality(0)?.label).toBe('Good');
    expect(airQuality(50)?.label).toBe('Good');
    expect(airQuality(51)?.label).toBe('Moderate');
    expect(airQuality(150)?.label).toBe('Unhealthy for sensitive groups');
    expect(airQuality(301)?.label).toBe('Hazardous');
    expect(airQuality(-1)).toBeNull();
  });

  it('rejects impossible humidity rather than banding it', () => {
    expect(humidity(101)).toBeNull();
    expect(humidity(45)?.label).toBe('Comfortable');
    expect(humidity(80)?.label).toBe('Very humid');
  });

  it('keeps "not raining" distinct from "not known"', () => {
    expect(rain(false)?.label).toBe('Dry');
    expect(rain(true)?.label).toBe('Raining');
  });

  it('applies the heat index only where it is defined', () => {
    // NWS table: 32 °C (90 °F) at 60% RH reads about 99-100 °F, 37-38 °C.
    const hot = feelsLike(32, 60)!;
    expect(hot.adjusted).toBe(true);
    expect(hot.celsius).toBeGreaterThan(37);
    expect(hot.celsius).toBeLessThan(39);
    expect(hot.label).toBe('Extreme caution');

    // Below 27 °C, or with humidity unknown, it is the plain temperature.
    expect(feelsLike(22, 70)).toMatchObject({ celsius: 22, adjusted: false });
    expect(feelsLike(33, null)).toMatchObject({ celsius: 33, adjusted: false });
  });

  it('summarises only what is known', () => {
    expect(summary({ temperature: 32, humidity: 60, airQuality: 42, rain: false })).toBe('Feels like 37°, air good.');
    expect(summary({ temperature: null, humidity: 60, airQuality: 120, rain: true })).toBe('Raining, air unhealthy for sensitive groups.');
  });
});
