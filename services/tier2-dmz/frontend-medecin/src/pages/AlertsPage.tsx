import { memo, useCallback, useMemo, useState } from 'react';
import { Link, useNavigate } from 'react-router';
import { ReasonList, SeverityBadge, TypeTag } from '../components/AlertBits';
import { RequestState } from '../components/RequestState';
import { APP_CONFIG } from '../config';
import { toAlertRow, type AlertRow } from '../domain/alertRow';
import { formatClock, formatDuration } from '../domain/time';
import { formatNumber } from '../domain/vitals';
import { useNow } from '../hooks/useNow';
import { useTitle } from '../hooks/useTitle';
import { alertStream } from '../realtime/alertStream';
import { useAlertStream, useLatencyStats } from '../realtime/useAlertStream';

type SeverityFilter = 'ALL' | 'CRITICAL' | 'WARNING';

const TARGET_S = APP_CONFIG.alerts.latencyTargetMs / 1000;
const MAX_DISPLAYED = APP_CONFIG.alerts.maxDisplayed;

/** Écran d'accueil : alertes récentes puis alertes poussées en direct par le WebSocket. */
export function AlertsPage() {
  useTitle('Alertes en direct');
  const alerts = useAlertStream((s) => s.alerts);
  const liveCount = useAlertStream((s) => s.liveCount);
  const recentLoading = useAlertStream((s) => s.recentLoading);
  const recentError = useAlertStream((s) => s.recentError);
  const recentLoadedAt = useAlertStream((s) => s.recentLoadedAt);
  const latency = useLatencyStats();
  const [filter, setFilter] = useState<SeverityFilter>('ALL');
  const navigate = useNavigate();
  const now = useNow();

  const counts = useMemo(() => {
    const critical = alerts.filter((a) => a.alert.severity === 'CRITICAL').length;
    return { ALL: alerts.length, CRITICAL: critical, WARNING: alerts.length - critical };
  }, [alerts]);

  const filtered = useMemo(() => {
    if (filter === 'ALL') return alerts;
    return alerts.filter((a) => (a.alert.severity === 'CRITICAL') === (filter === 'CRITICAL'));
  }, [alerts, filter]);

  const rows = useMemo(() => filtered.slice(0, MAX_DISPLAYED).map(toAlertRow), [filtered]);
  const open = useCallback(
    (patientId: string) => void navigate(`/patients/${encodeURIComponent(patientId)}`),
    [navigate],
  );

  const pct = latency.withinTargetPct;
  const pctClass = pct === null ? '' : pct >= 95 ? 'ink-ok' : 'ink-crit';

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Alertes en direct</h1>
          <p className="muted">
            Alertes poussées en temps réel par le service de notification. Objectif : moins de {TARGET_S} s entre la
            prise de la mesure et l'affichage.
          </p>
        </div>
      </header>

      <section className="tiles" aria-label="Indicateurs de la session">
        <Tile label="Reçues en direct" value={String(liveCount)} sub="depuis l'ouverture de la page" />
        <Tile
          label="Délai mesure → écran"
          value={formatDuration(latency.avgMs)}
          sub={`moyen · max ${formatDuration(latency.maxMs)}`}
        />
        <Tile
          label={`Dans l'objectif < ${TARGET_S} s`}
          value={pct === null ? '—' : `${formatNumber(pct, pct < 100 ? 1 : 0)} %`}
          valueClass={pctClass}
          sub={`sur ${latency.count} alerte(s) temps réel reçue(s)`}
        />
        <Tile
          label="Délai de détection"
          value={formatDuration(latency.avgDetectionMs)}
          sub="moyen (détection − mesure)"
        />
      </section>

      <div className="toolbar">
        <div className="segmented" role="group" aria-label="Filtrer par gravité">
          {(
            [
              ['ALL', 'Toutes', null],
              ['CRITICAL', 'Critiques', 'dot-crit'],
              ['WARNING', 'Avertissements', 'dot-warn'],
            ] as const
          ).map(([key, label, dot]) => (
            <button
              key={key}
              type="button"
              className={filter === key ? 'active' : undefined}
              aria-pressed={filter === key}
              onClick={() => setFilter(key)}
            >
              {dot && <span className={`dot ${dot}`} aria-hidden="true" />}
              {label} <span className="count">{counts[key]}</span>
            </button>
          ))}
        </div>
        <div className="toolbar-right">
          {recentLoadedAt !== null && (
            <span className="muted small">Historique chargé à {formatClock(recentLoadedAt)}</span>
          )}
          <button type="button" className="btn btn-ghost" onClick={alertStream.loadRecent} disabled={recentLoading}>
            Recharger
          </button>
        </div>
      </div>

      <RequestState
        loading={recentLoading && rows.length === 0}
        error={recentError}
        loadingText="Chargement des dernières alertes…"
        onRetry={alertStream.loadRecent}
      />

      {rows.length > 0 ? (
        <>
          <div className="card table-wrap">
            <table className="table alerts-table">
              <thead>
                <tr>
                  <th scope="col">Gravité</th>
                  <th scope="col">Patient</th>
                  <th scope="col">Bracelet</th>
                  <th scope="col">Motif(s)</th>
                  <th scope="col">Mesure</th>
                  <th
                    scope="col"
                    title="Détection = detected_at − measure_ts · Écran = réception par ce navigateur − measure_ts"
                  >
                    Délais
                  </th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <AlertTableRow key={row.id} row={row} flash={row.flashUntil > now} onOpen={open} />
                ))}
              </tbody>
            </table>
          </div>
          <p className="muted small footnote">
            {rows.length} alerte(s) affichée(s)
            {filtered.length > MAX_DISPLAYED ? ` (limité aux ${MAX_DISPLAYED} plus récentes)` : ''}. Les délais sont
            calculés avec l'horloge de ce poste : bracelets, serveurs et poste doivent être synchronisés (NTP).
          </p>
        </>
      ) : (
        !recentLoading &&
        !recentError && (
          <div className="card empty">
            <p>
              <strong>Aucune alerte {filter === 'ALL' ? '' : 'de ce niveau '}pour le moment.</strong>
            </p>
            <p className="muted">Les nouvelles alertes apparaîtront ici automatiquement.</p>
          </div>
        )
      )}
    </>
  );
}

function Tile({ label, value, sub, valueClass = '' }: { label: string; value: string; sub: string; valueClass?: string }) {
  return (
    <div className="tile">
      <span className="tile-label">{label}</span>
      <span className={`tile-value ${valueClass}`}>{value}</span>
      <span className="tile-sub">{sub}</span>
    </div>
  );
}

interface RowProps {
  row: AlertRow;
  flash: boolean;
  onOpen: (patientId: string) => void;
}

const AlertTableRow = memo(function AlertTableRow({ row, flash, onOpen }: RowProps) {
  const cls = ['clickable', row.critical ? 'row-crit' : 'row-warn', flash ? 'flash' : ''].join(' ');
  return (
    <tr className={cls} onClick={() => onOpen(row.patientId)}>
      <td>
        <SeverityBadge critical={row.critical} label={row.severityLabel} />
      </td>
      <td>
        <Link
          className="patient-link"
          to={`/patients/${encodeURIComponent(row.patientId)}`}
          onClick={(e) => e.stopPropagation()}
        >
          {row.patientId}
        </Link>
      </td>
      <td>
        <span className="mono">{row.braceletId}</span>
        <TypeTag highRisk={row.highRisk} label={row.typeLabel} />
      </td>
      <td>
        <ReasonList reasons={row.reasons} />
      </td>
      <td className="nowrap" title={row.measureFull}>
        {row.measureClock}
      </td>
      <td className="delays nowrap">
        <div>
          <span className="muted">Détection</span> {row.detection}
        </div>
        <EndToEnd row={row} />
      </td>
    </tr>
  );
});

function EndToEnd({ row }: { row: AlertRow }) {
  switch (row.e2eState) {
    case 'ok':
      return (
        <div className="ink-ok" title="Reçue en direct dans l'objectif">
          <span className="muted">Écran</span> {row.e2e} ✓
        </div>
      );
    case 'late':
      return (
        <div className="ink-crit" title="Reçue en direct au-delà de l'objectif">
          <span className="muted">Écran</span> {row.e2e} ✗
        </div>
      );
    case 'batch':
      return (
        <div
          className="muted"
          title="Mesure transmise dans un lot horaire (bracelet standard) : hors objectif temps réel"
        >
          Écran {row.e2e} (lot)
        </div>
      );
    default:
      return (
        <div className="muted" title="Alerte chargée depuis l'historique, non reçue en direct">
          Écran — (historique)
        </div>
      );
  }
}
