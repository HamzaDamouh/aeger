import { useEffect, useState } from 'react';
import { Link, useNavigate, useSearchParams } from 'react-router';
import { fetchPatients } from '../api/aeger';
import { toUiError, type UiError } from '../api/errors';
import { TypeTag } from '../components/AlertBits';
import { RequestState } from '../components/RequestState';
import { VitalValue } from '../components/VitalValue';
import { APP_CONFIG } from '../config';
import type { PatientSummary } from '../domain/models';
import { formatAgo, formatClock, formatFull, parseTs } from '../domain/time';
import { braceletTypeLabel } from '../domain/vitals';
import { useNow } from '../hooks/useNow';
import { useTitle } from '../hooks/useTitle';

const { pageSize, maxLimit, searchDebounceMs } = APP_CONFIG.patients;

interface Request {
  q: string;
  limit: number;
  /** Incrémenté pour relancer la même requête (« Réessayer »). */
  nonce: number;
}

/** Issue de la dernière requête terminée (l'état « chargement » s'en déduit). */
interface Outcome {
  request: Request;
  error: UiError | null;
}

/** Dernière liste reçue (conservée pendant une nouvelle recherche). */
interface Shown {
  list: PatientSummary[];
  /** `limit` de la requête qui a produit cette liste. */
  limit: number;
}

/** Recherche d'un patient (`GET /api/vitals/patients?q=&limit=`) avec ses dernières valeurs. */
export function PatientsPage() {
  useTitle('Patients');
  const [searchParams] = useSearchParams();
  const navigate = useNavigate();
  const now = useNow();
  const [query, setQuery] = useState(() => searchParams.get('q') ?? '');
  const [request, setRequest] = useState<Request>(() => ({ q: query.trim(), limit: pageSize, nonce: 0 }));
  const [outcome, setOutcome] = useState<Outcome | null>(null);
  const [shown, setShown] = useState<Shown | null>(null);

  // Saisie → requête après une courte pause (debounce), et `?q=` dans l'URL (retour arrière).
  useEffect(() => {
    const q = query.trim();
    const t = setTimeout(() => {
      setRequest((r) => (r.q === q ? r : { q, limit: pageSize, nonce: r.nonce }));
      void navigate({ search: q ? `?q=${encodeURIComponent(q)}` : '' }, { replace: true });
    }, searchDebounceMs);
    return () => clearTimeout(t);
  }, [query, navigate]);

  // Requête (la précédente est annulée si une nouvelle part).
  useEffect(() => {
    const ctrl = new AbortController();
    fetchPatients(request.q, request.limit, ctrl.signal).then(
      (list) => {
        setShown({ list, limit: request.limit });
        setOutcome({ request, error: null });
      },
      (err: unknown) => {
        if (!ctrl.signal.aborted) setOutcome({ request, error: toUiError(err) });
      },
    );
    return () => ctrl.abort();
  }, [request]);

  const retry = () => setRequest((r) => ({ ...r, nonce: r.nonce + 1 }));
  const showMore = () => setRequest((r) => ({ ...r, limit: Math.min(maxLimit, r.limit + pageSize) }));
  const open = (id: string) => void navigate(`/patients/${encodeURIComponent(id)}`);
  const loading = outcome?.request !== request;
  const error = loading ? null : (outcome?.error ?? null);
  const list = shown?.list ?? null;
  const canShowMore = shown !== null && shown.list.length >= shown.limit && shown.limit < maxLimit;

  return (
    <>
      <header className="page-head">
        <div>
          <h1>Patients</h1>
          <p className="muted">Recherchez un patient pour consulter sa fiche et son historique sur 7 jours.</p>
        </div>
      </header>

      <div className="toolbar">
        <label className="search">
          <span className="sr-only">Rechercher un patient</span>
          <input
            type="search"
            placeholder="Identifiant patient, ex. P00042"
            autoComplete="off"
            spellCheck={false}
            value={query}
            onChange={(e) => setQuery(e.target.value)}
          />
        </label>
        <div className="toolbar-right">
          {loading && list !== null && (
            <span className="loading inline">
              <span className="spinner" aria-hidden="true" />
              Recherche…
            </span>
          )}
        </div>
      </div>

      <RequestState loading={loading && list === null} error={error} loadingText="Chargement des patients…" onRetry={retry} />

      {list !== null && list.length > 0 && (
        <>
          <div className="card table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">Patient</th>
                  <th scope="col">Bracelet</th>
                  <th scope="col" className="num">FC <span className="unit">bpm</span></th>
                  <th scope="col" className="num">SpO₂ <span className="unit">%</span></th>
                  <th scope="col" className="num">Temp. <span className="unit">°C</span></th>
                  <th scope="col" className="num">PA <span className="unit">mmHg</span></th>
                  <th scope="col">Dernière mesure</th>
                </tr>
              </thead>
              <tbody>
                {list.map((p) => {
                  const ts = parseTs(p.last_ts ?? p.last?.ts);
                  return (
                    <tr key={p.patient_id} className="clickable" onClick={() => open(p.patient_id)}>
                      <td>
                        <Link
                          className="patient-link"
                          to={`/patients/${encodeURIComponent(p.patient_id)}`}
                          onClick={(e) => e.stopPropagation()}
                        >
                          {p.patient_id}
                        </Link>
                      </td>
                      <td>
                        <span className="mono">{p.bracelet_id}</span>
                        <TypeTag highRisk={p.type === 'high_risk'} label={braceletTypeLabel(p.type)} />
                      </td>
                      <td className="num"><VitalValue vital="heart_rate" value={p.last?.heart_rate} /></td>
                      <td className="num"><VitalValue vital="spo2" value={p.last?.spo2} /></td>
                      <td className="num"><VitalValue vital="temperature" value={p.last?.temperature} /></td>
                      <td className="num nowrap">
                        <VitalValue vital="systolic" value={p.last?.systolic} /> /{' '}
                        <VitalValue vital="diastolic" value={p.last?.diastolic} />
                      </td>
                      <td className="nowrap" title={formatFull(ts)}>
                        {formatAgo(ts, now)} <span className="muted small">({formatClock(ts)})</span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
          <div className="list-footer">
            <span className="muted small">{list.length} patient(s) affiché(s)</span>
            {canShowMore && (
              <button type="button" className="btn btn-ghost" onClick={showMore} disabled={loading}>
                Afficher plus
              </button>
            )}
          </div>
        </>
      )}

      {list !== null && list.length === 0 && !loading && !error && (
        <div className="card empty">
          <p>
            <strong>
              {request.q
                ? `Aucun patient ne correspond à « ${request.q} ».`
                : 'Aucun patient avec des mesures sur les 7 derniers jours.'}
            </strong>
          </p>
        </div>
      )}
    </>
  );
}
