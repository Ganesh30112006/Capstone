import { useMemo, useState } from 'react';
import { Circle, CircleMarker, MapContainer, Marker, Popup, TileLayer, useMap } from 'react-leaflet';
import L from 'leaflet';
import 'leaflet/dist/leaflet.css';
import { Crosshair, CloudSun } from 'lucide-react';
import type { Station, StationStatus } from '@/types/domain';
import { StatusDot } from './StatusIndicators';
import { reading, readingWithUnit } from '@/lib/format';
import {
  ANDHRA_PRADESH_BOUNDS,
  ANDHRA_PRADESH_CENTER,
  ANDHRA_PRADESH_ZOOM,
  POSITION_DRIFT_THRESHOLD_METRES,
  distanceMetres,
  formatDistance,
} from '@/lib/geo';
import {
  OPEN_WEATHER_ATTRIBUTION,
  WEATHER_OVERLAYS,
  weatherTileUrl,
  weatherTilesAvailable,
} from '@/lib/weather-tiles';
import { useViewerLocation } from '@/hooks/use-viewer-location';

function markerIcon(status: StationStatus, value?: string) {
  // The status is written onto the marker rather than a colour, so the page it
  // is drawn on decides how a status looks. A palette with no hues to spend
  // needs to say this in shape instead, and cannot if the fill is fixed here.
  return L.divIcon({
    className: 'station-marker',
    html: `<span class="station-marker-dot ${status}"></span>${value ? `<span class="station-marker-label">${value}</span>` : ''}`,
    iconSize: [14, 14],
    iconAnchor: [7, 7],
  });
}

/**
 * Frames the map on whatever there is to see.
 *
 * With stations plotted it fits them. With none it falls back to the bounds of
 * Andhra Pradesh, which is a statement about the region this console covers and
 * stays true whether or not anything has reported. The fallback it replaces was
 * the centroid of the contiguous United States.
 */
function FitBounds({ points }: { points: Array<[number, number]> }) {
  const map = useMap();
  useMemo(() => {
    if (points.length === 1) {
      map.setView(points[0], 11);
    } else if (points.length > 1) {
      map.fitBounds(L.latLngBounds(points), { padding: [32, 32] });
    } else {
      map.fitBounds(L.latLngBounds(ANDHRA_PRADESH_BOUNDS), { padding: [16, 16] });
    }
  }, [map, points]);
  return null;
}

export interface LeafletMapProps {
  stations: Station[];
  onSelectStation?: (uid: string) => void;
  /** Value badge shown on each pin, e.g. "18.6" or an AQI reading. */
  valueFor?: (station: Station) => string | undefined;
  heightClassName?: string;
  /** Weather overlays and the locate control are operator tools, not public ones. */
  showControls?: boolean;
}

/**
 * Real map view backed by React Leaflet and OpenStreetMap tiles, framed on
 * Andhra Pradesh. Only station identity and public-facing readings are ever
 * drawn on it: no internal infrastructure, no device credentials, nothing
 * beyond what the public observatory already shows.
 *
 * A station is plotted from the position its own GPS reported when there is
 * one, and from the registry otherwise. The popup says which, because "where we
 * recorded it" and "where it says it is" are different claims. Stations with
 * neither are not drawn, and are counted underneath, so the map cannot quietly
 * show a smaller fleet than exists.
 */
export function LeafletMap({
  stations,
  onSelectStation,
  valueFor,
  heightClassName,
  showControls = false,
}: LeafletMapProps) {
  const located = stations.filter((s) => s.position !== null);
  const unlocated = stations.length - located.length;
  const points: Array<[number, number]> = located.map((s) => [
    s.position!.latitude,
    s.position!.longitude,
  ]);

  const viewer = useViewerLocation();
  const [overlayId, setOverlayId] = useState<string | null>(null);
  const overlaysAvailable = weatherTilesAvailable();
  const overlay = WEATHER_OVERLAYS.find((o) => o.id === overlayId) ?? null;
  const overlayUrl = overlay ? weatherTileUrl(overlay) : null;

  const nearest = useMemo(() => {
    if (!viewer.position || !located.length) return null;
    return located
      .map((station) => ({
        station,
        metres: distanceMetres(viewer.position!, [
          station.position!.latitude,
          station.position!.longitude,
        ]),
      }))
      .sort((a, b) => a.metres - b.metres)[0];
  }, [viewer.position, located]);

  return (
    <div className={`leaflet-map-wrap ${heightClassName ?? ''}`} data-testid="leaflet-map">
      <MapContainer
        center={ANDHRA_PRADESH_CENTER}
        zoom={ANDHRA_PRADESH_ZOOM}
        scrollWheelZoom
        style={{ height: '100%', width: '100%' }}
      >
        <TileLayer
          className="map-base-tiles"
          attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
          url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
        />
        {overlayUrl && (
          <TileLayer
            key={overlay!.id}
            url={overlayUrl}
            opacity={0.6}
            attribution={OPEN_WEATHER_ATTRIBUTION}
          />
        )}
        <FitBounds points={points} />

        {located.map((station) => {
          const position = station.position!;
          const drifted =
            station.positionDriftMetres !== null &&
            station.positionDriftMetres > POSITION_DRIFT_THRESHOLD_METRES;
          return (
            <Marker
              key={station.uid}
              position={[position.latitude, position.longitude]}
              icon={markerIcon(station.status, valueFor?.(station))}
              eventHandlers={onSelectStation ? { click: () => onSelectStation(station.uid) } : undefined}
            >
              <Popup>
                <strong>{station.name}</strong>
                <br />
                <StatusDot status={station.status} /> {station.status} ·{' '}
                {readingWithUnit(station.temperature, '°', 1)} · AQI {reading(station.airQuality)}
                <br />
                <span className="map-popup-note" data-testid={`position-source-${station.uid}`}>
                  {position.source === 'reported'
                    ? 'Position reported by the station'
                    : 'Position from the device registry; the station has not reported one'}
                </span>
                {drifted && (
                  <>
                    <br />
                    <span className="map-popup-drift" data-testid={`drift-${station.uid}`}>
                      {formatDistance(station.positionDriftMetres!)} from its registered location
                    </span>
                  </>
                )}
                {nearest?.station.uid === station.uid && (
                  <>
                    <br />
                    <span className="map-popup-note">
                      Nearest to you · {formatDistance(nearest.metres)}
                    </span>
                  </>
                )}
              </Popup>
            </Marker>
          );
        })}

        {viewer.position && (
          <>
            {/* Drawn at the accuracy the browser reported rather than as a bare
                point, so the marker does not claim precision it does not have. */}
            {viewer.accuracyMetres !== null && (
              <Circle
                center={viewer.position}
                radius={viewer.accuracyMetres}
                pathOptions={{ color: '#3f6fb5', weight: 1, fillOpacity: 0.08 }}
              />
            )}
            <CircleMarker
              center={viewer.position}
              radius={5}
              pathOptions={{ color: '#3f6fb5', fillColor: '#3f6fb5', fillOpacity: 1 }}
            >
              <Popup>
                <strong>You are here</strong>
                <br />
                <span className="map-popup-note">
                  {viewer.accuracyMetres !== null
                    ? `Accurate to about ${formatDistance(viewer.accuracyMetres)}`
                    : 'Your browser did not report an accuracy for this fix'}
                </span>
              </Popup>
            </CircleMarker>
          </>
        )}
      </MapContainer>

      {showControls && (
        <div className="map-controls" data-testid="map-controls">
          <button
            type="button"
            className="button button-secondary"
            onClick={viewer.status === 'ready' ? viewer.clear : viewer.request}
            disabled={viewer.status === 'locating' || viewer.status === 'unsupported'}
            title={viewer.status === 'unsupported' ? 'This browser does not provide geolocation.' : undefined}
            data-testid="button-locate-me"
          >
            <Crosshair size={14} />
            {viewer.status === 'locating'
              ? 'Locating…'
              : viewer.status === 'ready'
                ? 'Hide my location'
                : 'Show my location'}
          </button>

          <div className="map-overlay-picker">
            <span className="map-overlay-label">
              <CloudSun size={14} /> Weather overlay
            </span>
            <div className="segmented">
              <button
                className={overlayId === null ? 'active' : ''}
                onClick={() => setOverlayId(null)}
                disabled={!overlaysAvailable}
                data-testid="button-overlay-none"
              >
                None
              </button>
              {WEATHER_OVERLAYS.map((item) => (
                <button
                  key={item.id}
                  className={overlayId === item.id ? 'active' : ''}
                  onClick={() => setOverlayId(item.id)}
                  disabled={!overlaysAvailable}
                  title={overlaysAvailable ? item.description : undefined}
                  data-testid={`button-overlay-${item.id}`}
                >
                  {item.label}
                </button>
              ))}
            </div>
          </div>
        </div>
      )}

      {showControls && !overlaysAvailable && (
        // A control that draws nothing is worse than one plainly switched off:
        // an empty precipitation overlay reads as "no rain anywhere".
        <p className="map-note" data-testid="text-overlay-unconfigured">
          Weather overlays need an OpenWeatherMap API key. Set{' '}
          <code>VITE_OPENWEATHER_API_KEY</code> to enable them.
        </p>
      )}

      {showControls && viewer.message && (
        <p className="map-note" data-testid="text-viewer-location-note">
          {viewer.message}
        </p>
      )}

      {showControls && overlay && (
        <p className="map-note" data-testid="text-overlay-provenance">
          {overlay.description}
        </p>
      )}

      {!located.length && (
        <div className="leaflet-map-empty">
          {stations.length
            ? 'No station has a usable position yet, so none can be placed on the map.'
            : 'No stations have a recorded location yet.'}
        </div>
      )}

      {Boolean(located.length) && unlocated > 0 && (
        // The map would otherwise show a smaller fleet than exists, with nothing
        // to say that anything had been left off it.
        <p className="map-note" data-testid="text-unlocated-stations">
          {unlocated} station{unlocated === 1 ? '' : 's'} not shown: no usable position.
        </p>
      )}
    </div>
  );
}
