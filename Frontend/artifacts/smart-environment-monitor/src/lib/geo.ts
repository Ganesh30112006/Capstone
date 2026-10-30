/**
 * Where this deployment is, and how far things are from each other.
 *
 * The map used to fall back to [39.5, -98.35] — the centroid of the contiguous
 * United States — whenever no station had a usable position. Every station in
 * this fleet is in coastal Andhra Pradesh, so the one time that fallback was
 * ever shown was the one time it was most misleading: an operator looking at an
 * empty map of Kansas has been told something false about where their network
 * is, on the screen whose entire job is to say where it is.
 *
 * A viewport is not a measurement. Framing the map on Andhra Pradesh says
 * "this is the region this console covers", which is a property of the
 * deployment and is true whether or not any station has reported. It never
 * stands in for a station's position: a station with no coordinates is absent
 * from the map and counted as such, never drawn at the centre of the frame.
 */

/** [south, west], [north, east] — the corners Leaflet wants. */
export type Bounds = [[number, number], [number, number]];

/**
 * Andhra Pradesh after the 2014 bifurcation: Ichchapuram in the north-east down
 * to the Chittoor tip, and west to the Anantapur edge.
 */
export const ANDHRA_PRADESH_BOUNDS: Bounds = [
  [12.62, 76.75],
  [19.92, 84.8],
];

/** Fallback view when nothing is plotted. The state, framed — not a place. */
export const ANDHRA_PRADESH_CENTER: [number, number] = [16.27, 80.78];

/** Wide enough to hold the whole state comfortably on a laptop viewport. */
export const ANDHRA_PRADESH_ZOOM = 7;

/**
 * Mainland India plus the island territories, used to sanity-check a position
 * before it is plotted. A station reporting from outside this box is far more
 * likely to be a swapped latitude/longitude pair or an uninitialised GPS fix
 * than a genuine relocation.
 */
export const INDIA_BOUNDS: Bounds = [
  [6.75, 68.16],
  [35.51, 97.4],
];

export function isWithin(bounds: Bounds, latitude: number, longitude: number): boolean {
  const [[south, west], [north, east]] = bounds;
  return latitude >= south && latitude <= north && longitude >= west && longitude <= east;
}

/**
 * A coordinate pair that could be a real place on Earth.
 *
 * (0, 0) is rejected outright. It is in the Gulf of Guinea, and a GPS module
 * that has not achieved a fix reports exactly that — so plotting it would put a
 * station in the Atlantic and make a failed fix look like a working one.
 */
export function isPlausibleCoordinate(
  latitude: number | null | undefined,
  longitude: number | null | undefined,
): latitude is number {
  if (typeof latitude !== 'number' || typeof longitude !== 'number') return false;
  if (!Number.isFinite(latitude) || !Number.isFinite(longitude)) return false;
  if (latitude === 0 && longitude === 0) return false;
  return latitude >= -90 && latitude <= 90 && longitude >= -180 && longitude <= 180;
}

const EARTH_RADIUS_METRES = 6_371_000;

const toRadians = (degrees: number) => (degrees * Math.PI) / 180;

/** Great-circle distance in metres. Accurate enough at fleet scale. */
export function distanceMetres(
  from: [number, number],
  to: [number, number],
): number {
  const [lat1, lon1] = from;
  const [lat2, lon2] = to;
  const dLat = toRadians(lat2 - lat1);
  const dLon = toRadians(lon2 - lon1);
  const a =
    Math.sin(dLat / 2) ** 2 +
    Math.cos(toRadians(lat1)) * Math.cos(toRadians(lat2)) * Math.sin(dLon / 2) ** 2;
  return 2 * EARTH_RADIUS_METRES * Math.asin(Math.min(1, Math.sqrt(a)));
}

/**
 * How far a station's reported position may sit from its registered one before
 * it is worth drawing attention to.
 *
 * Consumer GPS scatters by a few tens of metres even when the unit has not
 * moved, so a small difference is the instrument, not the station. 250 m is
 * comfortably outside that and well inside "this station is not where the
 * registry says it is".
 */
export const POSITION_DRIFT_THRESHOLD_METRES = 250;

/** Distance for a human: metres up close, kilometres beyond that. */
export function formatDistance(metres: number): string {
  if (!Number.isFinite(metres)) return '—';
  if (metres < 1000) return `${Math.round(metres)} m`;
  if (metres < 10_000) return `${(metres / 1000).toFixed(1)} km`;
  return `${Math.round(metres / 1000)} km`;
}
