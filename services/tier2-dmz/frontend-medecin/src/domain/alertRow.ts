import { APP_CONFIG } from '../config';
import type { Alert, ReceivedAlert } from './models';
import { formatClock, formatDuration, formatFull, parseTs } from './time';
import { braceletTypeLabel, formatReason, severityLabel } from './vitals';

/** Ligne d'alerte prête à afficher (écran « Alertes en direct » et fiche patient). */
export interface AlertRow {
  id: string;
  patientId: string;
  braceletId: string;
  severityLabel: string;
  critical: boolean;
  typeLabel: string;
  highRisk: boolean;
  reasons: string[];
  measureClock: string;
  measureFull: string;
  detection: string;
  live: boolean;
  e2e: string;
  /** ok / late = reçue en direct dans / hors objectif ; batch = lot horaire ; none = historique. */
  e2eState: 'ok' | 'late' | 'batch' | 'none';
  /** Jusqu'à quand faire clignoter la ligne (nouvelle alerte critique reçue en direct). */
  flashUntil: number;
}

export function toAlertRow(r: ReceivedAlert): AlertRow {
  const a = r.alert;
  const critical = a.severity === 'CRITICAL';
  let e2eState: AlertRow['e2eState'] = 'none';
  if (r.endToEndDelayMs !== null) {
    e2eState = !r.realtime ? 'batch' : r.endToEndDelayMs < APP_CONFIG.alerts.latencyTargetMs ? 'ok' : 'late';
  }
  return {
    id: a.alert_id,
    patientId: a.patient_id,
    braceletId: a.bracelet_id,
    severityLabel: severityLabel(a.severity),
    critical,
    typeLabel: braceletTypeLabel(a.type),
    highRisk: a.type === 'high_risk',
    reasons: a.reasons.length ? a.reasons.map(formatReason) : ['Seuil dépassé'],
    measureClock: formatClock(r.measureMs),
    measureFull: formatFull(r.measureMs),
    detection: formatDuration(r.detectionDelayMs),
    live: r.origin === 'live',
    e2e: formatDuration(r.endToEndDelayMs),
    e2eState,
    flashUntil: r.origin === 'live' && critical ? r.receivedAt + 4_000 : 0,
  };
}

/** Calcule les délais d'une alerte au moment de sa réception par le navigateur. */
export function toReceived(alert: Alert, origin: 'live' | 'history', receivedAt: number): ReceivedAlert {
  const measureMs = parseTs(alert.measure_ts);
  const detectedMs = parseTs(alert.detected_at);
  const hasMeasure = Number.isFinite(measureMs);
  return {
    alert,
    origin,
    receivedAt,
    measureMs,
    detectedMs,
    detectionDelayMs: hasMeasure && Number.isFinite(detectedMs) ? detectedMs - measureMs : null,
    endToEndDelayMs: origin === 'live' && hasMeasure ? receivedAt - measureMs : null,
    realtime:
      alert.type === 'high_risk' ||
      alert.measure?.anomaly === true ||
      alert.reasons.some((x) => x.parameter === 'anomaly'),
  };
}
