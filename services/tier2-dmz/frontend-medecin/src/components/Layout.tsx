import { Link, NavLink, Outlet } from 'react-router';
import { auth } from '../auth/auth';
import { useAuth } from '../auth/useAuth';
import { formatReason } from '../domain/vitals';
import { useNow } from '../hooks/useNow';
import { alertStream } from '../realtime/alertStream';
import { useAlertStream } from '../realtime/useAlertStream';
import { SeverityBadge } from './AlertBits';

/** Gabarit des écrans médecin : barre supérieure, bandeaux, contenu de la route. */
export function Layout() {
  const profile = useAuth((s) => s.profile);
  const sessionProblem = useAuth((s) => s.sessionProblem);
  const liveCount = useAlertStream((s) => s.liveCount);

  const logout = () => {
    alertStream.stop();
    auth.logout();
  };

  return (
    <>
      <header className="topbar">
        <Link className="brand" to="/">
          <span className="brand-mark" aria-hidden="true" />
          AEGER <span className="brand-sub">Espace médecin</span>
        </Link>
        <nav className="mainnav" aria-label="Navigation principale">
          <NavLink to="/" end>
            Alertes en direct
            {liveCount > 0 && (
              <span className="nav-count" aria-label={`${liveCount} alertes reçues`}>{liveCount}</span>
            )}
          </NavLink>
          <NavLink to="/patients">Patients</NavLink>
        </nav>
        <div className="topbar-right">
          <StreamStatus />
          <span className="user" title={profile?.email ?? profile?.username ?? ''}>
            <span className="user-name">{profile?.displayName}</span>
            <span className="user-role">Médecin</span>
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={logout}>
            Se déconnecter
          </button>
        </div>
      </header>

      {sessionProblem && (
        <div className="banner banner-warn" role="alert">
          <span>{sessionProblem} Reconnectez-vous pour continuer.</span>
          <button type="button" className="btn btn-sm" onClick={() => auth.login()}>
            Se reconnecter
          </button>
        </div>
      )}

      <CriticalBanner />

      <main className="content">
        <Outlet />
      </main>
    </>
  );
}

/** Indicateur de connexion du WebSocket (cliquer = réessayer tout de suite). */
function StreamStatus() {
  const status = useAlertStream((s) => s.status);
  const nextRetryAt = useAlertStream((s) => s.nextRetryAt);
  const now = useNow();
  const connected = status === 'connected';
  const label =
    status === 'connected'
      ? 'Temps réel : connecté'
      : status === 'connecting'
        ? 'Temps réel : connexion…'
        : status === 'stopped'
          ? 'Temps réel : arrêté'
          : 'Temps réel : reconnexion…';
  const title = connected
    ? 'WebSocket des alertes ouvert'
    : nextRetryAt !== null
      ? `Nouvelle tentative dans ${Math.max(0, Math.ceil((nextRetryAt - now) / 1000))} s — cliquer pour réessayer maintenant`
      : 'Connexion au WebSocket des alertes en cours';
  return (
    <button
      type="button"
      className={`ws-status ${connected ? 'ws-ok' : 'ws-retry'}`}
      title={title}
      onClick={alertStream.reconnectNow}
      aria-live="polite"
    >
      <span className="ws-dot" aria-hidden="true" />
      {label}
    </button>
  );
}

/** Bandeau affiché quelques secondes à la réception d'une alerte critique, sur tous les écrans. */
function CriticalBanner() {
  const banner = useAlertStream((s) => s.criticalBanner);
  if (!banner) return null;
  const reasons = banner.alert.reasons.map(formatReason).join(' · ') || 'seuil critique dépassé';
  return (
    <div className="banner banner-crit" role="alert">
      <SeverityBadge critical label="Critique" />
      <span>
        Nouvelle alerte critique — <strong>{banner.alert.patient_id}</strong> : {reasons}
      </span>
      <Link className="btn btn-sm" to={`/patients/${encodeURIComponent(banner.alert.patient_id)}`} onClick={alertStream.dismissBanner}>
        Voir le patient
      </Link>
      <button type="button" className="banner-close" aria-label="Fermer" onClick={alertStream.dismissBanner}>
        ×
      </button>
    </div>
  );
}
