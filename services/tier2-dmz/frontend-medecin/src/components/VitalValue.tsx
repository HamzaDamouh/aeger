import type { VitalKey } from '../domain/models';
import { classify, formatVital, levelText } from '../domain/vitals';

interface Props {
  vital: VitalKey;
  value: number | null | undefined;
}

/**
 * Valeur d'un paramètre vital, colorée selon les seuils du contrat ; le niveau est aussi porté
 * par une flèche (▲/▼) et un texte pour lecteur d'écran (jamais la couleur seule).
 */
export function VitalValue({ vital, value }: Props) {
  const c = classify(vital, value);
  const hint = levelText(c);
  const cls = c.level === 'critical' ? 'vv vv-crit' : c.level === 'warning' ? 'vv vv-warn' : 'vv';
  const arrow = c.level === 'critical' || c.level === 'warning' ? (c.direction === 'low' ? '▼' : '▲') : '';
  return (
    <span className={cls} title={hint || undefined}>
      {formatVital(vital, value)}
      {arrow && (
        <>
          <span className="vv-arrow" aria-hidden="true">{arrow}</span>
          <span className="sr-only"> ({hint})</span>
        </>
      )}
    </span>
  );
}
