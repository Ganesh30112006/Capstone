import { describe, it, expect, afterEach, vi } from 'vitest';
import {
  WEATHER_OVERLAYS,
  openWeatherApiKey,
  weatherTileUrl,
  weatherTilesAvailable,
} from './weather-tiles';

/**
 * An overlay that draws nothing is not a neutral failure. A precipitation layer
 * that renders no tiles reads as "no rain anywhere", which is a weather claim
 * the console has no basis for. So the key is either configured and the
 * overlays work, or it is not and the controls are plainly unavailable.
 */
describe('OpenWeatherMap overlay configuration', () => {
  const clouds = WEATHER_OVERLAYS.find((o) => o.id === 'clouds')!;

  afterEach(() => {
    vi.unstubAllEnvs();
  });

  it('is unavailable when no key is set', () => {
    vi.stubEnv('VITE_OPENWEATHER_API_KEY', '');
    expect(openWeatherApiKey()).toBeNull();
    expect(weatherTilesAvailable()).toBe(false);
    expect(weatherTileUrl(clouds)).toBeNull();
  });

  it('treats a leftover placeholder as not configured', () => {
    // A .env shipped with the placeholder still in it is a setup mistake, not a
    // key. Accepting it would produce tiles that 401 and silently draw nothing.
    vi.stubEnv('VITE_OPENWEATHER_API_KEY', 'your-api-key-here');
    expect(weatherTilesAvailable()).toBe(false);

    vi.stubEnv('VITE_OPENWEATHER_API_KEY', 'changeme');
    expect(weatherTilesAvailable()).toBe(false);
  });

  it('ignores surrounding whitespace', () => {
    vi.stubEnv('VITE_OPENWEATHER_API_KEY', '   ');
    expect(weatherTilesAvailable()).toBe(false);
  });

  it('builds a tile URL once a key is configured', () => {
    vi.stubEnv('VITE_OPENWEATHER_API_KEY', 'abc123');

    expect(weatherTilesAvailable()).toBe(true);
    const url = weatherTileUrl(clouds);
    expect(url).toContain('tile.openweathermap.org/map/clouds_new/{z}/{x}/{y}.png');
    expect(url).toContain('appid=abc123');
  });

  it('escapes a key rather than pasting it into the URL raw', () => {
    vi.stubEnv('VITE_OPENWEATHER_API_KEY', 'a key&with=chars');
    expect(weatherTileUrl(clouds)).toContain('appid=a%20key%26with%3Dchars');
  });

  it('offers one layer per overlay, each saying what it is', () => {
    expect(WEATHER_OVERLAYS.map((o) => o.layer)).toEqual([
      'clouds_new',
      'precipitation_new',
      'temp_new',
      'wind_new',
    ]);
    // Every overlay is modelled data, and each says so: what this platform
    // measured is the station markers, not the tiles under them.
    for (const overlay of WEATHER_OVERLAYS) {
      expect(overlay.description.length).toBeGreaterThan(0);
    }
  });
});
