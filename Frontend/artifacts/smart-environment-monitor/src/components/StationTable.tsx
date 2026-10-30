import { Link } from 'react-router-dom';
import { MapPinned, MoreHorizontal } from 'lucide-react';
import type { Station } from '@/types/domain';
import { readingNote, readingWithUnit } from '@/lib/format';
import { Badge, toneForStationStatus } from './StatusIndicators';

export function StationTable({ stations }: { stations: Station[] }) {
  return (
    <div className="data-table-wrap">
      <table className="data-table">
        <thead>
          <tr>
            <th>Station</th><th>Status</th><th>Temperature</th><th>Air quality</th><th>Battery</th><th>Last seen</th><th />
          </tr>
        </thead>
        <tbody>
          {stations.map((station) => (
            <tr key={station.uid}>
              <td>
                <Link to={`/admin/devices/${station.uid}`} className="table-station" data-testid={`link-table-station-${station.uid}`}>
                  <span className="table-station-icon"><MapPinned size={14} /></span>
                  <span><strong>{station.name}</strong><small>{station.uid}</small></span>
                </Link>
              </td>
              <td><Badge tone={toneForStationStatus(station.status)}>{station.status}</Badge></td>
              <td className="mono" title={readingNote(station.reading)}>{readingWithUnit(station.temperature, ' °C', 1)}</td>
              <td className="mono" title={readingNote(station.reading)}>{readingWithUnit(station.airQuality, ' AQI')}</td>
              <td>
                {station.battery === null ? (
                  <small className="muted-cell">Not reported</small>
                ) : (
                  <>
                    <div className="battery-bar"><span style={{ width: `${station.battery}%` }} className={station.battery < 40 ? 'warn' : ''} /></div>
                    <small>{station.battery}%</small>
                  </>
                )}
              </td>
              <td className="muted-cell">{station.lastSeen}</td>
              <td><button className="table-more" aria-label={`More actions for ${station.name}`} disabled title="This control is not wired to anything yet." data-testid={`button-station-more-${station.uid}`}><MoreHorizontal size={16} /></button></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
