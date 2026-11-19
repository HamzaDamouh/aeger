import { APP_CONFIG } from '../config';
import {
  isRecord,
  normalizeAlert,
  type Alert,
  type HistoryResponse,
  type PatientSummary,
} from '../domain/models';
import { apiGet } from './http';

/** Appels REST du contrat (Services/CONTRAT.md § 5), relatifs à l'origine courante (NGINX). */

/** `GET /api/alerts/recent` → 100 dernières alertes. */
export async function fetchRecentAlerts(signal?: AbortSignal): Promise<Alert[]> {
  const body = await apiGet<unknown>(APP_CONFIG.api.recentAlerts, undefined, signal);
  const list: unknown[] = Array.isArray(body)
    ? body
    : isRecord(body) && Array.isArray(body['alerts'])
      ? body['alerts']
      : [];
  return list.map(normalizeAlert).filter((a): a is Alert => a !== null);
}

/** `GET /api/vitals/patients?q=&limit=` → patients avec leur dernière mesure. */
export async function fetchPatients(q: string, limit: number, signal?: AbortSignal): Promise<PatientSummary[]> {
  const list = await apiGet<PatientSummary[] | null>(APP_CONFIG.api.patients, { q, limit }, signal);
  return Array.isArray(list) ? list : [];
}

/** `GET /api/vitals/patients/{id}/history?hours=h` (1 ≤ h ≤ 168). */
export async function fetchHistory(patientId: string, hours: number, signal?: AbortSignal): Promise<HistoryResponse> {
  const h = Math.min(168, Math.max(1, Math.round(hours)));
  const r = await apiGet<Partial<HistoryResponse> | null>(APP_CONFIG.api.history(patientId), { hours: h }, signal);
  return {
    patient_id: r?.patient_id ?? patientId,
    hours: r?.hours ?? h,
    bucket_seconds: r?.bucket_seconds ?? 0,
    points: Array.isArray(r?.points) ? r.points : [],
  };
}
