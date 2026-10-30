import { useState } from 'react';
import { useQuery } from '@tanstack/react-query';
import { Link } from 'react-router-dom';
import { ArrowRight, Download, RefreshCw, Sparkles } from 'lucide-react';
import { aiApi } from '@/api/aiApi';
import { AdminPageHeader } from '@/components/PageHeaders';
import { Badge, toneForSeverity } from '@/components/StatusIndicators';
import { LiveStatusPulse } from '@/components/LiveStatusPulse';
import { DataState } from '@/components/DataState';
import { useLiveStream } from '@/hooks/use-live-stream';

export function AIInsights() {
  const anomaliesQuery = useQuery({ queryKey: ['anomalies'], queryFn: aiApi.getAnomalies });
  const live = useLiveStream(true);
  const livePredictions = Object.values(live.predictions);
  const [exporting, setExporting] = useState<'idle' | 'busy' | 'failed'>('idle');

  // The raw receipts behind the public "AI check" page, for offline analysis.
  const exportReceipts = async () => {
    setExporting('busy');
    try {
      const blob = await aiApi.downloadForecastReceipts();
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = `forecast-receipts-${new Date().toISOString().slice(0, 19).replaceAll(':', '')}.csv`;
      a.click();
      URL.revokeObjectURL(url);
      setExporting('idle');
    } catch {
      setExporting('failed');
    }
  };

  // The banner states how much there is to look at, so it must not state a
  // number when the detector did not answer.
  const bannerHeadline = anomaliesQuery.isError
    ? 'Anomalies could not be read'
    : anomaliesQuery.isPending
      ? 'Checking for observations…'
      : `${anomaliesQuery.data.length} observation${anomaliesQuery.data.length === 1 ? '' : 's'} worth a look`;

  return (
    <>
      <AdminPageHeader
        eyebrow="DECISION SUPPORT · AI INSIGHTS"
        title="Anomalies, with a reason."
        description="Machine-assisted observations ranked by anomaly score. Every suggestion remains reviewable by an operator, and nothing here acts on the network by itself."
        actions={<>
          <button className="button button-secondary" onClick={exportReceipts} disabled={exporting === 'busy'} data-testid="button-export-receipts" title="Every recent forecast scored against the reading it predicted">
            <Download size={14} />{exporting === 'busy' ? 'Exporting…' : exporting === 'failed' ? 'Export failed, retry' : 'Forecast receipts (CSV)'}
          </button>
          <button className="button button-secondary" onClick={() => anomaliesQuery.refetch()} disabled={anomaliesQuery.isFetching} data-testid="button-refresh-insights"><RefreshCw size={14} />{anomaliesQuery.isFetching ? 'Refreshing…' : 'Refresh insights'}</button>
        </>}
      />
      <div className="ai-banner">
        <div className="ai-banner-mark"><Sparkles size={19} /></div>
        <div>
          <strong data-testid="ai-banner-headline">{bannerHeadline}</strong>
          {/* Score is a distance from the model's expectation — deliberately not called confidence. */}
          <p>
            {anomaliesQuery.isError
              ? 'The detector did not answer, so the absence of anomalies below means nothing either way.'
              : "Scores measure how far a reading sat from the model's expectation. They are not calibrated probabilities."}
          </p>
        </div>
        <LiveStatusPulse status={live.status} />
      </div>

      <div className="anomaly-list">
        <DataState
          query={anomaliesQuery}
          what="anomalies"
          empty={
            <div className="empty-state compact-empty">
              <Sparkles size={22} /><h3>No anomalies recorded</h3>
              <p>When the inference worker flags a reading, it will appear here with its score and reasoning.</p>
            </div>
          }
        >
          {(anomalies) => anomalies.map((anomaly) => (
            <div className="panel anomaly-card" key={anomaly.id} data-testid={`card-anomaly-${anomaly.id}`}>
              <div className="anomaly-top">
                <Badge tone={toneForSeverity(anomaly.severity)}>{anomaly.severity} · score {anomaly.score.toFixed(2)}</Badge>
                <span className="mono">{anomaly.timestamp}</span>
              </div>
              <h3>{anomaly.parameter} deviation at {anomaly.stationUid}</h3>
              <p>{anomaly.explanation}</p>
              <div className="anomaly-values">
                <span><small>Actual</small><strong>{anomaly.actual}</strong></span>
                <span><small>Expected</small><strong>{anomaly.expected}</strong></span>
                <Link className="button button-secondary" to={`/admin/devices/${anomaly.stationUid}`} data-testid={`button-review-anomaly-${anomaly.id}`}>
                  Review station <ArrowRight size={14} />
                </Link>
              </div>
            </div>
          ))}
        </DataState>
      </div>

      {livePredictions.length > 0 && (
        <div className="panel" style={{ marginTop: '1.5rem' }}>
          <div className="panel-heading">
            <div><div className="eyebrow">LIVE FORECASTS · STREAM WORKER</div><h3>Latest predictions</h3></div>
            <span className="panel-meta">{livePredictions.length} station{livePredictions.length === 1 ? '' : 's'}</span>
          </div>
          {livePredictions.map((prediction) => (
            <div className="simple-row" key={prediction.deviceUid}>
              <span>
                <strong>{prediction.deviceUid}</strong>
                <small>{prediction.method} · horizon {prediction.horizon} · {new Date(prediction.generatedAt).toLocaleTimeString()}</small>
              </span>
              <span className="mono">
                {Object.entries(prediction.predictions ?? {})
                  .map(([metric, values]) => `${metric} ${Array.isArray(values) && values.length ? Number(values[0]).toFixed(1) : '—'}`)
                  .join(' · ')}
              </span>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
