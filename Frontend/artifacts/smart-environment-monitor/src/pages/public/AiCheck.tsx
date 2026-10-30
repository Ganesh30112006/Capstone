import { useMemo, useState } from 'react';
import { Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts';
import { DataState, UnavailableNotice } from '@/components/DataState';
import type { BaselineName, ForecastMetric, ForecastSkill, MethodSkill, PairStats, SkillStats } from '@/api/publicApi';
import { usePublicForecastReceipts, usePublicForecastSkill, usePublicStations } from '@/hooks/use-public-data';
import { UNKNOWN } from '@/lib/format';
import { parseServerTime } from '@/lib/time';

/*
 * The AI, held to account in public.
 *
 * Every forecast the model makes is scored, when the reading it predicted
 * arrives, against that reading and against the simplest possible forecaster:
 * repeat the last value. The page reports what that scoring found -- including
 * when the answer is "no better than the simple guess", and including "not
 * enough evidence yet". A skill score is never shown before the service has
 * enough receipts to state one.
 */

const METRICS: Array<{ key: ForecastMetric; label: string; unit: string; digits: number }> = [
  { key: 'temperature', label: 'Temperature', unit: '°C', digits: 2 },
  { key: 'humidity', label: 'Humidity', unit: '%', digits: 2 },
  { key: 'airQuality', label: 'Air index', unit: '', digits: 1 },
  { key: 'pressure', label: 'Pressure', unit: 'hPa', digits: 2 },
  { key: 'oxygen', label: 'Oxygen', unit: '%', digits: 3 },
];

const MODEL = 'chronos-2';

const pct = (v: number | null | undefined) => (typeof v === 'number' && Number.isFinite(v) ? `${Math.round(v * 100)}%` : UNKNOWN);
const num = (v: number | null | undefined, d: number) => (typeof v === 'number' && Number.isFinite(v) ? v.toFixed(d) : UNKNOWN);

type Verdict = { word: string; tone: 'good' | 'bad' | 'even' | 'pending' };

const OPPONENTS: Array<{ key: BaselineName; label: string; waiting: string }> = [
  { key: 'persistence', label: 'Repeat last value', waiting: 'No forecasts scored yet' },
  { key: 'trend', label: 'Follow the trend', waiting: 'No forecasts scored yet' },
  { key: 'daily', label: 'Same time yesterday', waiting: 'Needs a day of readings' },
];

type Scored = Pick<PairStats, 'scored' | 'enough' | 'meanErrorReductionCi95'>;

/** Plain words for one group of receipts, decided by the interval, not the point estimate. */
export function verdictOf(stats: Scored | undefined, minScored: number): Verdict {
  if (!stats || stats.scored === 0) return { word: 'No forecasts scored yet', tone: 'pending' };
  if (!stats.enough) return { word: `Checking: ${stats.scored} of ${minScored} forecasts scored`, tone: 'pending' };
  const ci = stats.meanErrorReductionCi95;
  if (ci && ci[0] > 0) return { word: 'AI is better', tone: 'good' };
  if (ci && ci[1] < 0) return { word: 'AI is worse', tone: 'bad' };
  return { word: 'No clear difference yet', tone: 'even' };
}

/** A bar from the middle: right for skill above zero, left for below. */
function SkillBar({ skill }: { skill: number | null | undefined }) {
  const known = typeof skill === 'number' && Number.isFinite(skill);
  const clamped = known ? Math.max(-1, Math.min(1, skill)) : 0;
  return (
    <div className="skill-bar" aria-hidden="true">
      <span className="skill-bar-zero" />
      {known && (
        <span
          className={`skill-bar-fill ${clamped >= 0 ? 'up' : 'down'}`}
          style={clamped >= 0 ? { left: '50%', width: `${clamped * 50}%` } : { right: '50%', width: `${-clamped * 50}%` }}
        />
      )}
    </div>
  );
}

function Opponent({ stats, label, waiting, minScored }: { stats: PairStats | undefined; label: string; waiting: string; minScored: number }) {
  const verdict = !stats || stats.scored === 0 ? { word: waiting, tone: 'pending' as const } : verdictOf(stats, minScored);
  return (
    <div className="opponent">
      <span className="opponent-label">vs {label}</span>
      <SkillBar skill={stats?.enough ? stats.skill : null} />
      <span className="opponent-figures">
        <strong>{stats?.enough ? pct(stats.skill) : UNKNOWN}</strong>
        <span className={`verdict verdict-${verdict.tone}`}>{verdict.word}</span>
      </span>
    </div>
  );
}

function Scoreboard({ block, minScored }: { block: MethodSkill | undefined; minScored: number }) {
  return (
    <div className="score-list">
      {METRICS.map((m) => {
        const stats = block?.metrics[m.key];
        return (
          <div className="home-card score-row" key={m.key} data-testid={`score-${m.key}`}>
            <div className="score-name">
              <h3>{m.label}</h3>
              <span className="score-sub">
                AI error {num(stats?.maeModel, m.digits)}{stats?.maeModel !== undefined && m.unit ? ` ${m.unit}` : ''} · {stats?.scored ?? 0} checked
              </span>
            </div>
            {OPPONENTS.map((o) => (
              <Opponent key={o.key} stats={stats?.baselines?.[o.key]} label={o.label} waiting={o.waiting} minScored={minScored} />
            ))}
          </div>
        );
      })}
    </div>
  );
}

/** Where the scores come from, in time, and whether they are being kept. */
function StorageNote({ summary }: { summary: ForecastSkill | undefined }) {
  if (!summary) return null;
  const since = summary.since ? new Date(summary.since) : null;
  const sinceText = since && !Number.isNaN(since.getTime()) ? since.toLocaleString() : null;
  const kept = summary.storage?.enabled && summary.storage.state === 'ok';
  return (
    <p className="ai-storage" data-testid="text-ai-storage">
      {sinceText ? `Scores cover forecasts since ${sinceText}. ` : ''}
      {kept
        ? 'Every check is saved, so the record carries on across restarts.'
        : 'Checks are not being saved right now, so these scores only go back to when the service last started.'}
    </p>
  );
}

function StepChart({ byStep, minScored }: { byStep: Record<string, SkillStats> | undefined; minScored: number }) {
  const steps = Object.keys(byStep ?? {}).map(Number).sort((a, b) => a - b);
  if (!steps.length) return <p className="ai-empty">No forecasts have been scored for this metric yet.</p>;
  return (
    <div className="step-bars">
      {steps.map((step) => {
        const stats = byStep?.[String(step)];
        const skill = stats?.enough ? stats.skill ?? null : null;
        return (
          <div className="step-bar" key={step}>
            <SkillBar skill={skill} />
            <span className="step-bar-label">{step} ahead</span>
            <strong>{stats?.enough ? pct(skill) : `${stats?.scored ?? 0}/${minScored}`}</strong>
          </div>
        );
      })}
    </div>
  );
}

function RegimeCards({ byRegime, minScored }: { byRegime: Record<string, SkillStats> | undefined; minScored: number }) {
  const regimes: Array<[string, string, string]> = [
    ['calm', 'Calm', 'The reading had barely moved before the forecast.'],
    ['changing', 'Changing', 'The reading was moving when the forecast was made.'],
  ];
  return (
    <div className="regime-grid">
      {regimes.map(([key, title, note]) => {
        const stats = byRegime?.[key];
        const verdict = verdictOf(stats, minScored);
        return (
          <div className="home-card regime-card" key={key}>
            <h3>{title}</h3>
            <p>{note}</p>
            <strong className="regime-skill">{stats?.enough ? pct(stats.skill) : UNKNOWN}</strong>
            <span className={`verdict verdict-${verdict.tone}`}>{verdict.word}</span>
          </div>
        );
      })}
    </div>
  );
}

function RaceChart({ uid, metric }: { uid: string; metric: (typeof METRICS)[number] }) {
  const receipts = usePublicForecastReceipts(uid, metric.key);
  const data = useMemo(() => (receipts.data ?? []).map((r) => ({
    t: parseServerTime(r.recordedAt) ?? 0,
    actual: r.actual,
    ai: r.forecast,
    guess: r.persistenceAtIssue,
  })), [receipts.data]);

  return (
    <DataState
      query={receipts}
      what="This station's forecast history"
      empty={<p className="ai-empty">No forecasts have been scored for this station yet. They appear a few readings after it starts reporting.</p>}
    >
      {() => (
        <div className="race-chart" role="img" aria-label={`${metric.label}: real readings, the AI forecast and the simple guess, last ${data.length} readings`}>
          <ResponsiveContainer width="100%" height="100%">
            <LineChart data={data} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
              <XAxis dataKey="t" type="number" domain={['dataMin', 'dataMax']} hide />
              <YAxis domain={['auto', 'auto']} width={44} tickLine={false} axisLine={false} fontSize={12} />
              <Tooltip
                labelFormatter={(t: number) => new Date(t).toLocaleTimeString()}
                formatter={(v: number, name: string) => [`${v.toFixed(metric.digits)} ${metric.unit}`, name]}
              />
              <Line dataKey="guess" name="Simple guess" stroke="#94a3b8" strokeWidth={2} strokeDasharray="4 4" dot={false} isAnimationActive={false} />
              <Line dataKey="ai" name="AI forecast" stroke="#f7941d" strokeWidth={2.5} dot={false} isAnimationActive={false} />
              <Line dataKey="actual" name="Real reading" stroke="#1f2b4d" strokeWidth={2.5} dot={false} isAnimationActive={false} />
            </LineChart>
          </ResponsiveContainer>
        </div>
      )}
    </DataState>
  );
}

export function AiCheck() {
  const stationsQuery = usePublicStations();
  const skillQuery = usePublicForecastSkill();
  const stations = stationsQuery.data ?? [];
  const [uid, setUid] = useState('');
  const [metricKey, setMetricKey] = useState<ForecastMetric>('temperature');
  const station = stations.find((s) => s.uid === uid) ?? stations[0];
  const metric = METRICS.find((m) => m.key === metricKey) ?? METRICS[0];

  const summary = skillQuery.data;
  const block = summary?.methods[MODEL];
  const minScored = summary?.minScoredForSkill ?? 30;
  const otherMethods = Object.keys(summary?.methods ?? {}).filter((m) => m !== MODEL);
  const overall = block?.metrics[metric.key];

  return (
    <div className="home ai-page">
      <section className="ai-hero">
        <h1>Is the AI any good?</h1>
        <p className="home-lead">
          Every forecast is checked against the reading it predicted, and against three simple guesses that need no AI.
        </p>
        {skillQuery.isError ? (
          <UnavailableNotice what="The forecast scores" error={skillQuery.error} onRetry={skillQuery.refetch} />
        ) : (
          <div className="ai-headline">
            <div><strong data-numeric>{summary ? summary.totalScored.toLocaleString() : UNKNOWN}</strong><span>forecasts checked</span></div>
            <div><strong data-numeric>{overall?.enough ? pct(overall.winRate) : UNKNOWN}</strong><span>of the time the AI was closer ({metric.label.toLowerCase()})</span></div>
            <div><strong data-numeric>{overall?.enough ? pct(overall.skill) : UNKNOWN}</strong><span>less error than repeating the last value</span></div>
          </div>
        )}
        <StorageNote summary={summary} />
      </section>

      <section className="home-section" aria-labelledby="race-heading">
        <div className="home-section-head">
          <h2 id="race-heading">Forecast against reality</h2>
          <div className="ai-pickers">
            <select aria-label="Station" value={station?.uid ?? ''} onChange={(e) => setUid(e.target.value)} disabled={!stations.length}>
              {stations.map((s) => <option key={s.uid} value={s.uid}>{s.location}</option>)}
            </select>
            <select aria-label="Metric" value={metric.key} onChange={(e) => setMetricKey(e.target.value as ForecastMetric)}>
              {METRICS.map((m) => <option key={m.key} value={m.key}>{m.label}</option>)}
            </select>
          </div>
        </div>
        <div className="home-card ai-race">
          <div className="race-legend" aria-hidden="true">
            <span><i className="key key-actual" />Real reading</span>
            <span><i className="key key-ai" />AI forecast</span>
            <span><i className="key key-guess" />Simple guess</span>
          </div>
          {station ? <RaceChart uid={station.uid} metric={metric} /> : <p className="ai-empty">No stations yet.</p>}
        </div>
      </section>

      <section className="home-section" aria-labelledby="score-heading">
        <div className="home-section-head"><h2 id="score-heading">Scoreboard</h2></div>
        <DataState
          query={skillQuery}
          what="The forecast scores"
          empty={<p className="ai-empty">No forecasts have been scored yet.</p>}
          isEmpty={(data) => data.totalScored === 0}
        >
          {() => <Scoreboard block={block} minScored={minScored} />}
        </DataState>
      </section>

      <div className="ai-split">
        <section className="home-section" aria-labelledby="steps-heading">
          <div className="home-section-head"><h2 id="steps-heading">How far ahead can it see?</h2></div>
          <div className="home-card ai-panel">
            <StepChart byStep={block?.byStep[metric.key]} minScored={minScored} />
          </div>
        </section>
        <section className="home-section" aria-labelledby="regime-heading">
          <div className="home-section-head"><h2 id="regime-heading">Calm or changing</h2></div>
          <RegimeCards byRegime={block?.byRegime[metric.key]} minScored={minScored} />
        </section>
      </div>

      <section className="home-section" aria-labelledby="method-heading">
        <div className="home-section-head"><h2 id="method-heading">How this is measured</h2></div>
        <ul className="home-card ai-method">
          <li>The AI is Chronos-2, a time-series model used without any training on these stations.</li>
          <li>Each forecast is scored only by readings that arrive after it was made.</li>
          <li>It faces three simple opponents that need no AI: repeating the last value, following the recent trend, and the reading at the same time yesterday. Each uses only what was known before the reading it is scored on.</li>
          <li>Skill is 1 − AI error ÷ opponent error. 0% means no better than the opponent; below 0 means worse.</li>
          <li>Nothing is scored until {minScored} forecasts exist for it. {summary?.ciNote ?? ''}</li>
          {otherMethods.length > 0 && <li>Forecasts from the fallback ({otherMethods.join(', ')}) are scored separately and never counted as the AI's.</li>}
        </ul>
      </section>
    </div>
  );
}
