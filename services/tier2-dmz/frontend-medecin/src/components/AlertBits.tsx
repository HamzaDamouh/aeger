/** Petits éléments d'affichage partagés par les listes d'alertes. */

export function SeverityBadge({ critical, label }: { critical: boolean; label: string }) {
  return (
    <span className={critical ? 'badge badge-crit' : 'badge badge-warn'}>
      <span aria-hidden="true">{critical ? '▲' : '●'}</span> {label}
    </span>
  );
}

export function ReasonList({ reasons }: { reasons: string[] }) {
  return (
    <ul className="reasons">
      {reasons.map((r, i) => (
        <li key={i}>{r}</li>
      ))}
    </ul>
  );
}

export function TypeTag({ highRisk, label, large = false }: { highRisk: boolean; label: string; large?: boolean }) {
  return <span className={`tag${highRisk ? ' tag-hr' : ''}${large ? ' tag-lg' : ''}`}>{label}</span>;
}
