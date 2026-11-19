import { useMemo } from 'react';
import { APP_CONFIG } from '../config';
import { useStore } from '../lib/store';
import { alertStream, type AlertStreamState } from './alertStream';

/** État du flux d'alertes (sélecteur sur un champ de l'état). */
export function useAlertStream<S>(selector: (s: AlertStreamState) => S): S {
  return useStore(alertStream.store, selector);
}

export interface LatencyStats {
  count: number;
  avgMs: number | null;
  maxMs: number | null;
  withinTargetPct: number | null;
  avgDetectionMs: number | null;
}

/** Délais des alertes temps réel reçues en direct (objectif < 5 s). */
export function useLatencyStats(): LatencyStats {
  const alerts = useAlertStream((s) => s.alerts);
  return useMemo(() => {
    const live = alerts.filter((a) => a.origin === 'live' && a.realtime && a.endToEndDelayMs !== null);
    if (live.length === 0) {
      return { count: 0, avgMs: null, maxMs: null, withinTargetPct: null, avgDetectionMs: null };
    }
    const e2e = live.map((a) => a.endToEndDelayMs as number);
    const det = live.map((a) => a.detectionDelayMs).filter((d): d is number => d !== null);
    const within = e2e.filter((d) => d < APP_CONFIG.alerts.latencyTargetMs).length;
    return {
      count: live.length,
      avgMs: e2e.reduce((s, d) => s + d, 0) / e2e.length,
      maxMs: Math.max(...e2e),
      withinTargetPct: (within / e2e.length) * 100,
      avgDetectionMs: det.length ? det.reduce((s, d) => s + d, 0) / det.length : null,
    };
  }, [alerts]);
}
