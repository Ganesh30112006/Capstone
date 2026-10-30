/**
 * What a reading means for someone standing near the station, in plain words.
 *
 * Every function here returns null when its inputs are unknown. Guidance about
 * a reading that does not exist is a fabricated claim with extra steps: "Fine
 * for everyone outdoors" over an em dash says the air is clean when nobody
 * knows.
 *
 * The bands come from published scales, not from this project's opinion:
 *  - air quality: the US AQI categories. sensor-service bounds the index to
 *    0-500, the same scale. It is the station's own index from a low-cost
 *    sensor, so the copy says "on this station's index", never "official".
 *  - feels like: the NWS heat index (Rothfusz regression), which is only
 *    defined from about 27 °C and 40% humidity. Outside that range the
 *    temperature is returned unchanged and `adjusted` is false, so the page
 *    does not present a formula's output where the formula does not apply.
 */

export type Tone = 'calm' | 'notice' | 'caution' | 'danger';

export interface Interpretation {
  /** The band's name, as the scale names it. */
  label: string;
  /** One sentence a passer-by can act on. */
  advice: string;
  tone: Tone;
}

const known = (value: number | null | undefined): value is number =>
  typeof value === 'number' && Number.isFinite(value);

const AQI_BANDS: Array<{ max: number } & Interpretation> = [
  { max: 50, label: 'Good', advice: 'Fine for everyone outdoors.', tone: 'calm' },
  { max: 100, label: 'Moderate', advice: 'Fine for most people. Unusually sensitive people may notice it.', tone: 'notice' },
  { max: 150, label: 'Unhealthy for sensitive groups', advice: 'Children, older adults and people with asthma should ease off long efforts outdoors.', tone: 'caution' },
  { max: 200, label: 'Unhealthy', advice: 'Anyone may feel it. Cut long or heavy exertion outdoors.', tone: 'danger' },
  { max: 300, label: 'Very unhealthy', advice: 'Keep time outdoors short, and move exercise indoors.', tone: 'danger' },
  { max: Infinity, label: 'Hazardous', advice: 'Stay indoors and keep windows closed.', tone: 'danger' },
];

export function airQuality(index: number | null | undefined): Interpretation | null {
  if (!known(index) || index < 0) return null;
  const { label, advice, tone } = AQI_BANDS.find((band) => index <= band.max)!;
  return { label, advice, tone };
}

export function humidity(percent: number | null | undefined): Interpretation | null {
  if (!known(percent) || percent < 0 || percent > 100) return null;
  if (percent < 30) return { label: 'Dry', advice: 'Dry air. Skin and eyes may feel it.', tone: 'notice' };
  if (percent <= 60) return { label: 'Comfortable', advice: 'A comfortable level of moisture.', tone: 'calm' };
  if (percent <= 75) return { label: 'Humid', advice: 'Sticky. Sweat dries slowly.', tone: 'notice' };
  return { label: 'Very humid', advice: 'Muggy. The body cools itself poorly; drink water.', tone: 'caution' };
}

export function rain(detected: boolean | null | undefined): Interpretation | null {
  if (detected === null || detected === undefined) return null;
  return detected
    ? { label: 'Raining', advice: 'The station is detecting rain. Take cover or an umbrella.', tone: 'notice' }
    : { label: 'Dry', advice: 'No rain on the station’s sensor.', tone: 'calm' };
}

export interface FeelsLike extends Interpretation {
  /** °C. Equal to the air temperature when `adjusted` is false. */
  celsius: number;
  /** Whether the heat index applied, i.e. whether this differs from a plain reading. */
  adjusted: boolean;
}

/** NWS heat index, computed in °F as published, returned in °C. */
function heatIndexC(tempC: number, rh: number): number {
  const t = tempC * 9 / 5 + 32;
  const hi = -42.379 + 2.04901523 * t + 10.14333127 * rh - 0.22475541 * t * rh
    - 6.83783e-3 * t * t - 5.481717e-2 * rh * rh + 1.22874e-3 * t * t * rh
    + 8.5282e-4 * t * rh * rh - 1.99e-6 * t * t * rh * rh;
  return (hi - 32) * 5 / 9;
}

export function feelsLike(tempC: number | null | undefined, rh: number | null | undefined): FeelsLike | null {
  if (!known(tempC)) return null;
  const applies = known(rh) && rh >= 40 && rh <= 100 && tempC >= 27;
  const celsius = applies ? Math.max(tempC, heatIndexC(tempC, rh as number)) : tempC;

  if (!applies) {
    if (tempC < 10) return { celsius, adjusted: false, label: 'Cold', advice: 'Cold. Wear a layer.', tone: 'notice' };
    if (tempC < 27) return { celsius, adjusted: false, label: 'Mild', advice: 'Mild and easy to be out in.', tone: 'calm' };
    return { celsius, adjusted: false, label: 'Warm', advice: 'Warm. Drink water if you are out for long.', tone: 'notice' };
  }
  if (celsius < 32) return { celsius, adjusted: true, label: 'Caution', advice: 'Warm. Long effort outdoors gets tiring.', tone: 'notice' };
  if (celsius < 41) return { celsius, adjusted: true, label: 'Extreme caution', advice: 'Heat cramps are possible. Take shade and water breaks.', tone: 'caution' };
  if (celsius < 54) return { celsius, adjusted: true, label: 'Danger', advice: 'Heat exhaustion is likely with effort. Stay in the shade.', tone: 'danger' };
  return { celsius, adjusted: true, label: 'Extreme danger', advice: 'Heat stroke risk. Stay indoors and cool.', tone: 'danger' };
}

/**
 * One line for the whole station: how it feels and how the air is. Null when
 * neither is known, so the caller shows the dash instead of a sentence.
 */
export function summary(input: {
  temperature: number | null; humidity: number | null; airQuality: number | null; rain: boolean | null;
}): string | null {
  const feel = feelsLike(input.temperature, input.humidity);
  const air = airQuality(input.airQuality);
  const wet = input.rain === true;
  const parts: string[] = [];
  if (feel) parts.push(feel.adjusted ? `Feels like ${Math.round(feel.celsius)}°` : feel.label);
  if (wet) parts.push('raining');
  if (air) parts.push(`air ${air.label.toLowerCase()}`);
  if (!parts.length) return null;
  const line = parts.join(', ');
  return `${line.charAt(0).toUpperCase()}${line.slice(1)}.`;
}
