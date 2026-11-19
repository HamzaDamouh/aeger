import { useCallback, useEffect, useMemo, useState, type ReactNode } from 'react';
import { Link, useNavigate, useParams, useSearchParams } from 'react-router';
import { fetchHistory, fetchPatients } from '../api/aeger';
import { toUiError, type UiError } from '../api/errors';
import { ReasonList, SeverityBadge, TypeTag } from '../components/AlertBits';
import { RequestState } from '../components/RequestState';
import { SERIES_COLORS } from '../components/chartTheme';
import { VitalChart, type ChartSeries, type ThresholdLine, type XY } from '../components/VitalChart';
import { VitalValue } from '../components/VitalValue';
import { APP_CONFIG } from '../config';
import { toAlertRow } from '../domain/alertRow';
import type { HistoryPoint, HistoryResponse, PatientSummary, VitalKey } from '../domain/models';
import { formatAgo, formatBucket, formatClock, formatFull, parseTs } from '../domain/time';
import { THRESHOLDS, VITALS, braceletTypeLabel, classify, formatNumber, levelText, type Level } from '../domain/vitals';
import { useNow } from '../hooks/useNow';
import { useTitle } from '../hooks/useTitle';
import { useAlertStream } from '../realtime/useAlertStream';

const PERIODS = APP_CONFIG.history.periods;
const AUTO_REFRESH_MS = APP_CONFIG.history.autoRefreshMs;

/** Déclencheur de rechargement : manuel (bouton) ou automatique (toutes les 30 s, onglet visible). */
interface Tick {
  n: number;
  kind: 'manual' | 'auto';
}

/** Fiche patient `/patients/:id?h=1|24|168`. */
export function PatientPage() {
  const { id = '' } = useParams();
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const now = useNow();
  useTitle(`Patient ${id}`);

  const requested = Number(searchParams.get('h'));
  const hours = PERIODS.some((p) => p.hours === requested) ? requested : APP_CONFIG.history.defaultHours;

  const [tick, setTick] = useState<Tick>({ n: 0, kind: 'manual' });
  const refresh = useCallback(() => setTick((t) => ({ n: t.n + 1, kind: 'manual' })), []);
  useEffect(() => {
    const timer = setInterval(() => {
      if (document.visibilityState === 'visible') setTick((t) => ({ n: t.n + 1, kind: 'auto' }));
    }, AUTO_REFRESH_MS);
    return () => clearInterval(timer);
  }, [id, hours]);

  const summary = useSummary(id, tick);
  const history = useHistory(id, hours, tick);
  const charts = useMemo(() => (history.data ? buildCharts(history.data) : null), [history.data]);

  const allAlerts = useAlertStream((s) => s.alerts);
  const patientAlerts = useMemo(
    () => allAlerts.filter((a) => a.alert.patient_id === id).map(toAlertRow),
    [allAlerts, id],
  );

  const setPeriod = (h: number) => void navigate({ search: `?h=${h}` }, { replace: true });
  const s = summary.status === 'ok' ? summary.data : null;
  const lastTs = parseTs(s?.last_ts ?? s?.last?.ts);

  return (
    <>
      <nav className="crumbs" aria-label="Fil d'Ariane">
        <Link to="/patients">Patients</Link> <span aria-hidden="true">›</span> <span>{id}</span>
      </nav>

      <header className="page-head">
        <div>
          <h1>
            Patient {id}
            {s && <TypeTag highRisk={s.type === 'high_risk'} label={braceletTypeLabel(s.type)} large />}
          </h1>
          <p className="muted">
            {s ? (
              <>
                Bracelet <span className="mono">{s.bracelet_id}</span> · dernière mesure {formatAgo(lastTs, now)}{' '}
                <span className="small">({formatFull(lastTs)})</span>
              </>
            ) : (
              'Historique des paramètres vitaux (7 jours au maximum).'
            )}
          </p>
        </div>
        <div className="head-actions">
          <button type="button" className="btn btn-ghost" onClick={refresh} disabled={history.loading}>
            Actualiser
          </button>
        </div>
      </header>

      {summary.status === 'loading' && <RequestState loading loadingText="Chargement des dernières valeurs…" />}
      {summary.status === 'error' && <RequestState error={summary.error} onRetry={refresh} />}
      {summary.status === 'notfound' && (
        <div className="notice notice-info">
          <span className="notice-icon" aria-hidden="true">i</span>
          <div className="notice-body">Aucune mesure récente connue pour ce patient.</div>
        </div>
      )}
      {s && <LatestValues summary={s} />}

      <section className="section">
        <div className="toolbar">
          <div className="segmented" role="group" aria-label="Période affichée">
            {PERIODS.map((p) => (
              <button
                key={p.hours}
                type="button"
                className={hours === p.hours ? 'active' : undefined}
                aria-pressed={hours === p.hours}
                onClick={() => setPeriod(p.hours)}
              >
                {p.label}
              </button>
            ))}
          </div>
          <div className="toolbar-right">
            {history.loading && charts ? (
              <span className="loading inline">
                <span className="spinner" aria-hidden="true" />
                Mise à jour…
              </span>
            ) : history.updatedAt !== null ? (
              <span className="muted small">
                Mis à jour à {formatClock(history.updatedAt)} · actualisation automatique toutes les{' '}
                {AUTO_REFRESH_MS / 1000} s
              </span>
            ) : null}
          </div>
        </div>

        {history.refreshError && (
          <p className="small ink-crit">Dernière actualisation automatique en échec : {history.refreshError.message}</p>
        )}

        <RequestState
          loading={history.loading && !charts}
          error={history.error}
          loadingText="Chargement de l'historique…"
          onRetry={refresh}
        />

        {charts && (
          <>
            <p className="legend muted small">
              {charts.bucket && `Moyennes par intervalle de ${charts.bucket} (${charts.pointCount} points). `}
              Lignes de référence (seuils d'alerte) :
              <span className="legend-item">
                <span className="legend-line legend-warn" aria-hidden="true" />
                avertissement
              </span>
              <span className="legend-item">
                <span className="legend-line legend-crit" aria-hidden="true" />
                critique
              </span>
            </p>
            <div className={`charts-grid${history.loading ? ' stale' : ''}`}>
              {charts.defs.map((d) => (
                <figure key={d.key} className="card chart-card">
                  <figcaption>
                    <span className="chart-title">
                      {d.title} <span className="unit">({d.unit})</span>
                    </span>
                    {d.series.length > 1 && (
                      <span className="series-legend">
                        {d.series.map((sr) => (
                          <span key={sr.label} className="legend-item">
                            <span className="legend-swatch" style={{ background: sr.color }} aria-hidden="true" />
                            {sr.label}
                          </span>
                        ))}
                      </span>
                    )}
                  </figcaption>
                  {d.hasData ? (
                    <>
                      <VitalChart
                        series={d.series}
                        thresholds={d.thresholds}
                        xMin={charts.xMin}
                        xMax={charts.xMax}
                        hours={charts.hours}
                        unit={d.unit}
                        decimals={d.decimals}
                        suggestedMin={d.suggestedMin}
                        suggestedMax={d.suggestedMax}
                        gapMs={charts.gapMs}
                        ariaLabel={`${d.title} sur la période. ${d.stats}`}
                      />
                      <p className="chart-stats small muted">{d.stats}</p>
                    </>
                  ) : (
                    <div className="chart-empty muted">Aucune mesure sur la période.</div>
                  )}
                </figure>
              ))}
            </div>
          </>
        )}
      </section>

      <section className="section">
        <h2>
          Alertes de ce patient pendant la session <span className="count">{patientAlerts.length}</span>
        </h2>
        {patientAlerts.length === 0 ? (
          <p className="muted">Aucune alerte reçue pour ce patient depuis l'ouverture de l'application.</p>
        ) : (
          <div className="card table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Gravité</th>
                  <th scope="col">Motif(s)</th>
                  <th scope="col">Mesure</th>
                  <th scope="col">Délais</th>
                  <th scope="col">Origine</th>
                </tr>
              </thead>
              <tbody>
                {patientAlerts.map((row) => (
                  <tr key={row.id} className={row.critical ? 'row-crit' : 'row-warn'}>
                    <td>
                      <SeverityBadge critical={row.critical} label={row.severityLabel} />
                    </td>
                    <td>
                      <ReasonList reasons={row.reasons} />
                    </td>
                    <td className="nowrap" title={row.measureFull}>
                      {row.measureClock}
                    </td>
                    <td className="nowrap small">
                      Détection {row.detection}
                      {row.live && ` · écran ${row.e2e}`}
                    </td>
                    <td>{row.live ? 'Direct' : 'Historique'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </>
  );
}

/* ------------------------------------------------------------------ dernières valeurs */

function LatestValues({ summary }: { summary: PatientSummary }) {
  const last = summary.last;
  const sys = classify('systolic', last?.systolic);
  const card = (vital: 'heart_rate' | 'spo2' | 'temperature', label: string) => {
    const c = classify(vital, last?.[vital]);
    return (
      <ValueCard key={vital} label={label} level={c.level} status={statusText(c.level, levelText(c))}>
        <VitalValue vital={vital} value={last?.[vital]} />
        <span className="vc-unit">{VITALS[vital].unit}</span>
      </ValueCard>
    );
  };
  return (
    <section className="cards-row" aria-label="Dernières valeurs">
      {card('heart_rate', 'Fréquence cardiaque')}
      {card('spo2', 'SpO₂')}
      {card('temperature', 'Température')}
      <ValueCard
        label="Pression artérielle"
        level={sys.level}
        status={statusText(sys.level, levelText(sys) && `systolique ${levelText(sys)}`)}
      >
        <VitalValue vital="systolic" value={last?.systolic} /> / <VitalValue vital="diastolic" value={last?.diastolic} />
        <span className="vc-unit">mmHg</span>
      </ValueCard>
      {last?.anomaly && (
        <div className="value-card vc-warn">
          <span className="vc-label">Bracelet</span>
          <span className="vc-value small-value">Anomalie signalée</span>
          <span className="vc-sub">dernière mesure envoyée hors lot</span>
        </div>
      )}
    </section>
  );
}

function ValueCard(props: { label: string; level: Level; status: string; children: ReactNode }) {
  const cls = props.level === 'critical' ? ' vc-crit' : props.level === 'warning' ? ' vc-warn' : '';
  return (
    <div className={`value-card${cls}`}>
      <span className="vc-label">{props.label}</span>
      <span className="vc-value">{props.children}</span>
      <span className="vc-sub">{props.status}</span>
    </div>
  );
}

function statusText(level: Level, detail: string): string {
  if (level === 'none') return 'pas de valeur';
  return level === 'ok' ? 'dans les seuils' : detail;
}

/* ------------------------------------------------------------------ chargement des données */

type SummaryState =
  | { status: 'loading' }
  | { status: 'ok'; data: PatientSummary }
  | { status: 'notfound' }
  | { status: 'error'; error: UiError };

/** Dernières valeurs du patient, via la recherche par identifiant (pas rechargées au changement de période). */
function useSummary(id: string, tick: Tick): SummaryState {
  const [result, setResult] = useState<{ id: string; state: SummaryState } | null>(null);
  useEffect(() => {
    const ctrl = new AbortController();
    fetchPatients(id, 10, ctrl.signal).then(
      (list) => {
        const wanted = id.toUpperCase();
        const found = list.find((p) => p.patient_id.toUpperCase() === wanted);
        setResult({ id, state: found ? { status: 'ok', data: found } : { status: 'notfound' } });
      },
      (err: unknown) => {
        if (ctrl.signal.aborted) return;
        // Échec d'une actualisation : on garde les valeurs déjà affichées.
        setResult((prev) =>
          prev?.id === id && prev.state.status === 'ok' ? prev : { id, state: { status: 'error', error: toUiError(err) } },
        );
      },
    );
    return () => ctrl.abort();
  }, [id, tick]);
  return result?.id === id ? result.state : { status: 'loading' };
}

type HistoryData = { res: HistoryResponse; fetchedAt: number; hours: number };

interface HistoryRequest {
  id: string;
  hours: number;
  tick: Tick;
}

/** Issue de la dernière requête d'historique terminée. */
interface HistoryResult {
  req: HistoryRequest;
  data: HistoryData | null;
  error: UiError | null;
  refreshError: UiError | null;
  updatedAt: number | null;
}

interface HistoryView {
  data: HistoryData | null;
  loading: boolean;
  error: UiError | null;
  /** Échec d'une actualisation automatique (les courbes déjà affichées sont conservées). */
  refreshError: UiError | null;
  updatedAt: number | null;
}

/**
 * Historique du patient. Nouveau patient : les courbes du précédent ne sont pas montrées ;
 * nouvelle période : anciennes courbes grisées jusqu'à l'arrivée des nouvelles ; actualisation
 * automatique silencieuse.
 */
function useHistory(id: string, hours: number, tick: Tick): HistoryView {
  const req = useMemo<HistoryRequest>(() => ({ id, hours, tick }), [id, hours, tick]);
  const [result, setResult] = useState<HistoryResult | null>(null);
  useEffect(() => {
    const ctrl = new AbortController();
    fetchHistory(req.id, req.hours, ctrl.signal).then(
      (res) => {
        const at = Date.now();
        setResult({ req, data: { res, fetchedAt: at, hours: req.hours }, error: null, refreshError: null, updatedAt: at });
      },
      (err: unknown) => {
        if (ctrl.signal.aborted) return;
        const e = toUiError(err);
        setResult((prev) => {
          const sameParams = prev !== null && prev.req.id === req.id && prev.req.hours === req.hours;
          if (sameParams && prev.data && req.tick.kind === 'auto') {
            return { ...prev, req, refreshError: e };
          }
          return { req, data: sameParams ? prev.data : null, error: e, refreshError: null, updatedAt: prev?.updatedAt ?? null };
        });
      },
    );
    return () => ctrl.abort();
  }, [req]);

  if (result === null || result.req.id !== id) {
    return { data: null, loading: true, error: null, refreshError: null, updatedAt: null };
  }
  const sameParams = result.req.hours === hours;
  const settled = result.req === req;
  const silent = sameParams && tick.kind === 'auto';
  return {
    data: result.data,
    loading: !settled && !silent,
    error: settled ? result.error : null,
    refreshError: sameParams ? result.refreshError : null,
    updatedAt: result.updatedAt,
  };
}

/* ------------------------------------------------------------------ graphiques */

interface ChartDef {
  key: string;
  title: string;
  unit: string;
  decimals: number;
  series: ChartSeries[];
  thresholds: ThresholdLine[];
  suggestedMin: number;
  suggestedMax: number;
  stats: string;
  hasData: boolean;
}

interface ChartsModel {
  xMin: number;
  xMax: number;
  hours: number;
  gapMs: number;
  bucket: string;
  pointCount: number;
  defs: ChartDef[];
}

function num(v: unknown): number | null {
  const n = typeof v === 'string' ? Number(v) : v;
  return typeof n === 'number' && Number.isFinite(n) ? n : null;
}

function thresholdLines(key: VitalKey): ThresholdLine[] {
  const t = THRESHOLDS[key];
  const lines: ThresholdLine[] = [];
  if (t.warningLow !== undefined) lines.push({ value: t.warningLow, level: 'warning', side: 'low' });
  if (t.warningHigh !== undefined) lines.push({ value: t.warningHigh, level: 'warning', side: 'high' });
  if (t.criticalLow !== undefined) lines.push({ value: t.criticalLow, level: 'critical', side: 'low' });
  if (t.criticalHigh !== undefined) lines.push({ value: t.criticalHigh, level: 'critical', side: 'high' });
  return lines;
}

function rangeText(values: number[], decimals: number): string {
  if (values.length === 0) return '—';
  let min = Infinity;
  let max = -Infinity;
  let sum = 0;
  for (const v of values) {
    min = Math.min(min, v);
    max = Math.max(max, v);
    sum += v;
  }
  return `min ${formatNumber(min, decimals)} · moy. ${formatNumber(sum / values.length, decimals)} · max ${formatNumber(max, decimals)}`;
}

function buildCharts({ res, fetchedAt, hours }: { res: HistoryResponse; fetchedAt: number; hours: number }): ChartsModel {
  const pts = res.points
    .map((p) => ({ ms: parseTs(p.ts), p }))
    .filter((x) => Number.isFinite(x.ms))
    .sort((a, b) => a.ms - b.ms);

  // Écart au-delà duquel la courbe est interrompue : 3 intervalles d'agrégation, ou 3 fois
  // l'écart médian entre mesures (bracelets standards : mesures espacées).
  const deltas: number[] = [];
  for (let i = 1; i < pts.length; i++) deltas.push((pts[i]?.ms ?? 0) - (pts[i - 1]?.ms ?? 0));
  deltas.sort((a, b) => a - b);
  const median = deltas[Math.floor(deltas.length / 2)] ?? 0;
  const gapMs = Math.max(res.bucket_seconds * 1000 * 3, median * 3, 15_000);

  const series = (key: keyof Omit<HistoryPoint, 'ts'>): XY[] => pts.map(({ ms, p }) => ({ x: ms, y: num(p[key]) }));
  const values = (xy: XY[]): number[] => xy.map((v) => v.y).filter((y): y is number => y !== null);

  const single = (
    key: 'heart_rate' | 'spo2' | 'temperature',
    title: string,
    suggestedMin: number,
    suggestedMax: number,
  ): ChartDef => {
    const info = VITALS[key];
    const data = series(key);
    const vals = values(data);
    return {
      key,
      title,
      unit: info.unit,
      decimals: info.decimals,
      series: [{ label: info.short, color: SERIES_COLORS.primary, points: data }],
      thresholds: thresholdLines(key),
      suggestedMin,
      suggestedMax,
      stats: `${rangeText(vals, info.decimals)} ${info.unit}`,
      hasData: vals.length > 0,
    };
  };

  const sys = series('systolic');
  const dia = series('diastolic');
  const sysVals = values(sys);
  const diaVals = values(dia);

  return {
    xMin: fetchedAt - hours * 3_600_000,
    xMax: fetchedAt,
    hours,
    gapMs,
    bucket: formatBucket(res.bucket_seconds),
    pointCount: pts.length,
    defs: [
      single('heart_rate', 'Fréquence cardiaque', 30, 160),
      single('spo2', 'SpO₂', 80, 100),
      single('temperature', 'Température', 33.5, 41),
      {
        key: 'bp',
        title: 'Pression artérielle',
        unit: 'mmHg',
        decimals: 0,
        series: [
          { label: 'Systolique', color: SERIES_COLORS.dark, points: sys },
          { label: 'Diastolique', color: SERIES_COLORS.light, points: dia },
        ],
        // Le contrat ne fixe des seuils que pour la systolique.
        thresholds: thresholdLines('systolic'),
        suggestedMin: 40,
        suggestedMax: 210,
        stats: `Systolique ${rangeText(sysVals, 0)} · Diastolique ${rangeText(diaVals, 0)} mmHg`,
        hasData: sysVals.length > 0 || diaVals.length > 0,
      },
    ],
  };
}
