import type { AlertReason, VitalKey } from './models';

/** Seuils d'alerte du contrat (Services/CONTRAT.md § 4). « Bas » = règle `<`, « haut » = règle `>`. */
export interface Thresholds {
  warningLow?: number;
  warningHigh?: number;
  criticalLow?: number;
  criticalHigh?: number;
}

export const THRESHOLDS: Record<VitalKey, Thresholds> = {
  heart_rate: { warningLow: 40, warningHigh: 130, criticalLow: 35, criticalHigh: 150 },
  spo2: { warningLow: 90, criticalLow: 85 },
  temperature: { warningLow: 35.0, warningHigh: 39.5, criticalLow: 34.0, criticalHigh: 40.5 },
  systolic: { warningLow: 90, warningHigh: 180, criticalLow: 80, criticalHigh: 200 },
  diastolic: {},
};

export interface VitalInfo {
  label: string;
  short: string;
  unit: string;
  decimals: number;
}

export const VITALS: Record<VitalKey, VitalInfo> = {
  heart_rate: { label: 'Fréquence cardiaque', short: 'FC', unit: 'bpm', decimals: 0 },
  spo2: { label: 'Saturation en oxygène', short: 'SpO₂', unit: '%', decimals: 0 },
  temperature: { label: 'Température', short: 'Temp.', unit: '°C', decimals: 1 },
  systolic: { label: 'Pression systolique', short: 'PA sys.', unit: 'mmHg', decimals: 0 },
  diastolic: { label: 'Pression diastolique', short: 'PA dia.', unit: 'mmHg', decimals: 0 },
};

export type Level = 'ok' | 'warning' | 'critical' | 'none';

export interface Classification {
  level: Level;
  direction: 'low' | 'high' | null;
}

/** Classe une valeur selon les seuils du contrat (même logique que le service alerting). */
export function classify(key: VitalKey, value: number | null | undefined): Classification {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return { level: 'none', direction: null };
  }
  const t = THRESHOLDS[key];
  if (t.criticalLow !== undefined && value < t.criticalLow) return { level: 'critical', direction: 'low' };
  if (t.criticalHigh !== undefined && value > t.criticalHigh) return { level: 'critical', direction: 'high' };
  if (t.warningLow !== undefined && value < t.warningLow) return { level: 'warning', direction: 'low' };
  if (t.warningHigh !== undefined && value > t.warningHigh) return { level: 'warning', direction: 'high' };
  return { level: 'ok', direction: null };
}

export function levelText(c: Classification): string {
  if (c.level === 'critical') {
    return c.direction === 'low' ? 'sous le seuil critique' : 'au-dessus du seuil critique';
  }
  if (c.level === 'warning') {
    return c.direction === 'low' ? "sous le seuil d'avertissement" : "au-dessus du seuil d'avertissement";
  }
  return '';
}

const numberFormats = new Map<number, Intl.NumberFormat>();

/** Nombre au format français (virgule décimale). */
export function formatNumber(value: number | null | undefined, decimals = 0): string {
  if (value === null || value === undefined || !Number.isFinite(value)) {
    return '—';
  }
  let f = numberFormats.get(decimals);
  if (!f) {
    f = new Intl.NumberFormat('fr-FR', { minimumFractionDigits: decimals, maximumFractionDigits: decimals });
    numberFormats.set(decimals, f);
  }
  return f.format(value);
}

export function formatVital(key: VitalKey, value: number | null | undefined): string {
  return formatNumber(value, VITALS[key].decimals);
}

function isVitalKey(p: string): p is VitalKey {
  return p in VITALS;
}

/** Règle « < 90 » → « < 90 », « > 40.5 » → « > 40,5 ». */
function formatRule(rule: string): string {
  const m = /^\s*([<>]=?|==|=)\s*(-?\d+(?:\.(\d+))?)\s*$/.exec(rule);
  if (!m) {
    return rule;
  }
  return `${m[1]} ${formatNumber(Number(m[2]), m[3]?.length ?? 0)}`;
}

/** Motif d'alerte en français, ex. « SpO₂ 84 % (< 90) ». */
export function formatReason(r: AlertReason): string {
  const p = r.parameter;
  if (p === 'anomaly') {
    return 'Anomalie signalée par le bracelet';
  }
  const rule = r.rule ? ` (${formatRule(r.rule)})` : '';
  if (isVitalKey(p)) {
    const info = VITALS[p];
    const value = typeof r.value === 'number' ? formatNumber(r.value, info.decimals) : String(r.value ?? '—');
    return `${info.short} ${value} ${info.unit}${rule}`;
  }
  return `${p} ${String(r.value ?? '')}${rule}`.trim();
}

export function severityLabel(severity: string): string {
  if (severity === 'CRITICAL') return 'Critique';
  if (severity === 'WARNING') return 'Avertissement';
  return severity;
}

export function braceletTypeLabel(type: string | undefined): string {
  if (type === 'high_risk') return 'Haut risque';
  if (type === 'standard') return 'Standard';
  return type || '—';
}
