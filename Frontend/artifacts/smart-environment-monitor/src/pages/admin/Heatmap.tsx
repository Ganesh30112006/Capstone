import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { CircleMarker, MapContainer, Popup, TileLayer } from 'react-leaflet';
import 'leaflet/dist/leaflet.css';
import { analyticsApi } from '@/api/analyticsApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { UnavailableNotice } from '@/components/DataState';
import { UNKNOWN } from '@/lib/format';
import { ANDHRA_PRADESH_CENTER, ANDHRA_PRADESH_ZOOM } from '@/lib/geo';

const PARAMETERS = ['temperature', 'humidity', 'airQuality', 'pressure'] as const;
type Parameter = (typeof PARAMETERS)[number];

const LABELS: Record<Parameter, string> = {
  temperature: 'Temperature',
  humidity: 'Humidity',
  airQuality: 'Air quality',
  pressure: 'Pressure',
};

/** Three bands only — a continuous gradient would imply a precision the
 * sampled station network does not have. */
function band(value: number, min: number, max: number): 'low' | 'medium' | 'high' {
  if (max === min) return 'medium';
  const ratio = (value - min) / (max - min);
  return ratio < 0.34 ? 'low' : ratio < 0.67 ? 'medium' : 'high';
}

const BAND_COLOR = { low: '#477b9c', medium: '#b77a28', high: '#b0392f' } as const;

export function Heatmap() {
  const [parameter, setParameter] = useState<Parameter>('temperature');
  const heatmapQuery = useQuery({
    queryKey: ['heatmap', parameter],
    queryFn: () => analyticsApi.getHeatmap(parameter),
  });
  const { data, isPending } = heatmapQuery;

  const points = data?.points ?? [];
  const values = points.map((p) => p.value);
  const min = values.length ? Math.min(...values) : 0;
  const max = values.length ? Math.max(...values) : 0;
  // The fallback was the centroid of the contiguous United States, which is
  // where this map pointed on the one occasion it had nothing to plot. Framing
  // an empty heatmap on Andhra Pradesh says which region has no readings yet,
  // rather than naming a country the fleet has never been in.
  const center: [number, number] = points.length
    ? [points[0].latitude, points[0].longitude]
    : ANDHRA_PRADESH_CENTER;

  return (
    <>
      <AdminPageHeader
        eyebrow="SPATIAL ANALYSIS · HEATMAP"
        title="Where the readings cluster."
        description="Station readings placed geographically and banded into low, moderate, and high. Each circle is one station, not an interpolated surface."
        actions={
          <div className="segmented">
            {PARAMETERS.map((p) => (
              <button key={p} className={parameter === p ? 'active' : ''} onClick={() => setParameter(p)} data-testid={`button-heatmap-${p}`}>
                {LABELS[p]}
              </button>
            ))}
          </div>
        }
      />
      {isPending ? (
        <div className="empty-inline">Loading {LABELS[parameter].toLowerCase()} readings…</div>
      ) : heatmapQuery.isError ? (
        // An empty map here would read as "no station reported this parameter",
        // which is a finding. Not being able to ask is not a finding.
        <UnavailableNotice what={`The ${LABELS[parameter].toLowerCase()} surface`} error={heatmapQuery.error} onRetry={heatmapQuery.refetch} />
      ) : points.length ? (
        <div className="leaflet-map-wrap map-height-admin" data-testid="heatmap-map">
          <MapContainer center={center} zoom={ANDHRA_PRADESH_ZOOM} scrollWheelZoom style={{ height: '100%', width: '100%' }}>
            <TileLayer
              attribution='&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors'
              url="https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png"
            />
            {points.map((point, index) => {
              const tier = band(point.value, min, max);
              return (
                <CircleMarker
                  key={`${point.latitude}-${point.longitude}-${index}`}
                  center={[point.latitude, point.longitude]}
                  radius={18}
                  pathOptions={{ color: BAND_COLOR[tier], fillColor: BAND_COLOR[tier], fillOpacity: 0.35, weight: 1 }}
                >
                  <Popup>{LABELS[parameter]}: <strong>{point.value}</strong></Popup>
                </CircleMarker>
              );
            })}
          </MapContainer>
        </div>
      ) : (
        <div className="empty-state">
          <h3>No spatial readings yet</h3>
          <p>Stations need a recorded location and a recent reading before they appear on the heatmap.</p>
        </div>
      )}
      <div className="map-legend">
        <span><span className="heat-swatch low" />Low {heatmapQuery.isError ? `(${UNKNOWN})` : values.length ? `(${min})` : ''}</span>
        <span><span className="heat-swatch medium" />Moderate</span>
        <span><span className="heat-swatch high" />High {heatmapQuery.isError ? `(${UNKNOWN})` : values.length ? `(${max})` : ''}</span>
      </div>
    </>
  );
}
