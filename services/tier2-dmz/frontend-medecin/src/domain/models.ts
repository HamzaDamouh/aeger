/** Types des messages échangés avec les services (Services/CONTRAT.md § 3, § 4, § 5). */

export type VitalKey = 'heart_rate' | 'spo2' | 'temperature' | 'systolic' | 'diastolic';

/** Mesure d'un bracelet (§ 3). Champs optionnels côté client, par robustesse. */
export interface Measure {
  bracelet_id?: string;
  patient_id?: string;
  type?: string;
  seq?: number;
  ts?: string;
  heart_rate?: number | null;
  spo2?: number | null;
  temperature?: number | null;
  systolic?: number | null;
  diastolic?: number | null;
  anomaly?: boolean;
  source?: string;
  received_at?: string;
}

export interface AlertReason {
  parameter: string;
  value: number | string | boolean | null;
  rule: string;
}

/** Alerte produite par alerting et poussée par notification (§ 4). */
export interface Alert {
  alert_id: string;
  patient_id: string;
  bracelet_id: string;
  type: string;
  severity: string;
  measure_ts: string;
  detected_at: string;
  reasons: AlertReason[];
  measure?: Measure;
}

/** Élément de `GET /api/vitals/patients` (§ 5). */
export interface PatientSummary {
  patient_id: string;
  bracelet_id: string;
  type: string;
  last_ts: string | null;
  last: Measure | null;
}

export interface HistoryPoint {
  ts: string;
  heart_rate: number | null;
  spo2: number | null;
  temperature: number | null;
  systolic: number | null;
  diastolic: number | null;
}

/** Réponse de `GET /api/vitals/patients/{id}/history?hours=h` (§ 5). */
export interface HistoryResponse {
  patient_id: string;
  hours: number;
  bucket_seconds: number;
  points: HistoryPoint[];
}

/** Alerte telle que conservée par le navigateur, avec les délais mesurés. */
export interface ReceivedAlert {
  alert: Alert;
  /** `live` = reçue par le WebSocket ; `history` = chargée depuis `/api/alerts/recent`. */
  origin: 'live' | 'history';
  /** Horodatage local (ms) de réception par le navigateur. */
  receivedAt: number;
  measureMs: number;
  detectedMs: number;
  /** detected_at − measure_ts (ms), null si non calculable. */
  detectionDelayMs: number | null;
  /** réception navigateur − measure_ts (ms), uniquement pour les alertes reçues en direct. */
  endToEndDelayMs: number | null;
  /**
   * Vrai si la mesure a été transmise immédiatement (bracelet haut risque ou anomalie d'un
   * bracelet standard) : seules ces alertes relèvent de l'objectif « < 5 s ». Les mesures
   * envoyées dans le lot horaire arrivent par construction avec retard.
   */
  realtime: boolean;
}

export function isRecord(v: unknown): v is Record<string, unknown> {
  return typeof v === 'object' && v !== null && !Array.isArray(v);
}

function str(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

/** Accepte l'alerte nue (contrat) ou enveloppée (`{"alert": …}` / `{"data": …}` / `{"payload": …}`). */
function unwrap(raw: unknown): Record<string, unknown> | null {
  if (!isRecord(raw)) {
    return null;
  }
  if ('patient_id' in raw || 'alert_id' in raw) {
    return raw;
  }
  for (const key of ['alert', 'data', 'payload']) {
    const inner = raw[key];
    if (isRecord(inner)) {
      return inner;
    }
  }
  return null;
}

/** Normalise un objet reçu (WebSocket ou REST) en alerte ; null s'il est inexploitable. */
export function normalizeAlert(raw: unknown): Alert | null {
  const obj = unwrap(raw);
  if (!obj) {
    return null;
  }
  const measure = isRecord(obj['measure']) ? (obj['measure'] as Measure) : undefined;
  const patientId = str(obj['patient_id']) ?? measure?.patient_id;
  const measureTs = str(obj['measure_ts']) ?? measure?.ts;
  if (!patientId || !measureTs) {
    return null;
  }
  const braceletId = str(obj['bracelet_id']) ?? measure?.bracelet_id ?? '';
  const reasons: AlertReason[] = Array.isArray(obj['reasons'])
    ? obj['reasons'].filter(isRecord).map((r) => ({
        parameter: String(r['parameter'] ?? ''),
        value: (r['value'] ?? null) as AlertReason['value'],
        rule: String(r['rule'] ?? ''),
      }))
    : [];
  return {
    alert_id: str(obj['alert_id']) ?? `${braceletId}:${measure?.seq ?? measureTs}:${patientId}`,
    patient_id: patientId,
    bracelet_id: braceletId,
    type: str(obj['type']) ?? measure?.type ?? '',
    severity: (str(obj['severity']) ?? 'WARNING').toUpperCase(),
    measure_ts: measureTs,
    detected_at: str(obj['detected_at']) ?? '',
    reasons,
    measure,
  };
}
