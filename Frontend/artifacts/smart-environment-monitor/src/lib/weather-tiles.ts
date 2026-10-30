/**
 * OpenWeatherMap raster overlays drawn on top of the base map.
 *
 * These are weather *tiles* — cloud cover, precipitation, temperature and wind
 * rendered as images over the same OpenStreetMap base the stations sit on. They
 * are context, never measurement: what this platform actually measured is the
 * station markers, and a model's idea of the rainfall over a district is a
 * different kind of claim from a rain sensor on a pole in it.
 *
 * The tile endpoint is OpenWeatherMap's Weather Maps 1.0. There is no 0.6 of
 * it; 0.6 is the version of the OpenStreetMap *data* API, which is a different
 * service that serves map geometry rather than weather, and authenticates with
 * OAuth rather than a key.
 *
 * The key is read from the environment and is never committed. Without one the
 * overlays are not available, and the UI must say so rather than offering a
 * control that silently draws nothing — an empty overlay is indistinguishable
 * from clear skies.
 */

export interface WeatherOverlay {
  id: string;
  label: string;
  /** OpenWeatherMap layer name in the tile path. */
  layer: string;
  description: string;
}

export const WEATHER_OVERLAYS: WeatherOverlay[] = [
  {
    id: 'clouds',
    label: 'Cloud cover',
    layer: 'clouds_new',
    description: 'Modelled cloud cover, not observed by any station.',
  },
  {
    id: 'precipitation',
    label: 'Precipitation',
    layer: 'precipitation_new',
    description: 'Modelled precipitation. Station rain sensors are the measured record.',
  },
  {
    id: 'temp',
    label: 'Temperature',
    layer: 'temp_new',
    description: 'Modelled surface temperature, for regional context only.',
  },
  {
    id: 'wind',
    label: 'Wind speed',
    layer: 'wind_new',
    description: 'Modelled wind speed. This fleet carries no anemometers.',
  },
];

/**
 * The configured key, or null.
 *
 * Read through a helper rather than inline so the "no key" path is one thing
 * every caller tests against, instead of each component inventing its own idea
 * of what an unset key looks like.
 */
export function openWeatherApiKey(): string | null {
  const raw = import.meta.env.VITE_OPENWEATHER_API_KEY;
  if (typeof raw !== 'string') return null;
  const key = raw.trim();
  // A placeholder left in an .env file is not a key. Treating one as configured
  // would turn a setup mistake into tiles that silently 401 and draw nothing.
  if (!key || key.toLowerCase().startsWith('your') || key.toLowerCase() === 'changeme') {
    return null;
  }
  return key;
}

export function weatherTilesAvailable(): boolean {
  return openWeatherApiKey() !== null;
}

/** Tile URL template for Leaflet, or null when no key is configured. */
export function weatherTileUrl(overlay: WeatherOverlay): string | null {
  const key = openWeatherApiKey();
  if (!key) return null;
  return `https://tile.openweathermap.org/map/${overlay.layer}/{z}/{x}/{y}.png?appid=${encodeURIComponent(key)}`;
}

export const OPEN_WEATHER_ATTRIBUTION =
  'Weather overlay &copy; <a href="https://openweathermap.org/">OpenWeatherMap</a>';
