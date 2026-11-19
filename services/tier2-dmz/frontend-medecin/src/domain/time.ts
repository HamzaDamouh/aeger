/** Formatage des dates et durées en français (Intl). */

const timeFmt = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit', second: '2-digit' });
const dayFmt = new Intl.DateTimeFormat('fr-FR', { day: '2-digit', month: '2-digit' });
const fullFmt = new Intl.DateTimeFormat('fr-FR', {
  weekday: 'short',
  day: '2-digit',
  month: '2-digit',
  year: 'numeric',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
});
const hmFmt = new Intl.DateTimeFormat('fr-FR', { hour: '2-digit', minute: '2-digit' });
const weekdayDayFmt = new Intl.DateTimeFormat('fr-FR', { weekday: 'short', day: '2-digit', month: '2-digit' });
const tooltipFmt = new Intl.DateTimeFormat('fr-FR', {
  weekday: 'short',
  day: '2-digit',
  month: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
});

export function parseTs(ts: string | null | undefined): number {
  return ts ? Date.parse(ts) : NaN;
}

function isToday(ms: number): boolean {
  const d = new Date(ms);
  const n = new Date();
  return d.getFullYear() === n.getFullYear() && d.getMonth() === n.getMonth() && d.getDate() === n.getDate();
}

/** « 14:03:05 » aujourd'hui, « 17/11 14:03:05 » sinon. */
export function formatClock(ms: number): string {
  if (!Number.isFinite(ms)) return '—';
  return isToday(ms) ? timeFmt.format(ms) : `${dayFmt.format(ms)} ${timeFmt.format(ms)}`;
}

export function formatFull(ms: number): string {
  return Number.isFinite(ms) ? fullFmt.format(ms) : '—';
}

export const formatHourMinute = (ms: number): string => hmFmt.format(ms);
export const formatWeekdayDay = (ms: number): string => weekdayDayFmt.format(ms);
export const formatTooltipDate = (ms: number): string => tooltipFmt.format(ms);

function seconds(ms: number, digits: number): string {
  return (ms / 1000).toLocaleString('fr-FR', { minimumFractionDigits: digits, maximumFractionDigits: digits });
}

/** Durée courte : « 0,84 s », « 12,3 s », « 4 min 05 s », « 1 h 12 min ». */
export function formatDuration(ms: number | null | undefined): string {
  if (ms === null || ms === undefined || !Number.isFinite(ms)) return '—';
  const sign = ms < 0 ? '−' : '';
  const a = Math.abs(ms);
  if (a < 10_000) return `${sign}${seconds(a, 2)} s`;
  if (a < 60_000) return `${sign}${seconds(a, 1)} s`;
  const totalSec = Math.round(a / 1000);
  if (totalSec < 3600) {
    return `${sign}${Math.floor(totalSec / 60)} min ${String(totalSec % 60).padStart(2, '0')} s`;
  }
  const h = Math.floor(totalSec / 3600);
  const m = Math.floor((totalSec % 3600) / 60);
  return `${sign}${h} h ${String(m).padStart(2, '0')} min`;
}

/** « il y a 12 s », « il y a 3 min », « il y a 2 h », « il y a 3 j ». */
export function formatAgo(ms: number, now: number): string {
  if (!Number.isFinite(ms)) return '—';
  const s = Math.floor(Math.max(0, now - ms) / 1000);
  if (s < 60) return `il y a ${s} s`;
  const m = Math.floor(s / 60);
  if (m < 60) return `il y a ${m} min`;
  const h = Math.floor(m / 60);
  if (h < 48) return `il y a ${h} h`;
  return `il y a ${Math.floor(h / 24)} j`;
}

/** Taille d'intervalle d'agrégation : « 30 s », « 5 min », « 1 h ». */
export function formatBucket(sec: number | null | undefined): string {
  if (!sec || !Number.isFinite(sec)) return '';
  if (sec < 60) return `${sec} s`;
  if (sec < 3600) return `${Math.round(sec / 60)} min`;
  return `${Math.round(sec / 3600)} h`;
}
