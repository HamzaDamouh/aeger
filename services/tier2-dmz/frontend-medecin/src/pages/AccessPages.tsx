import type { ReactNode } from 'react';
import { auth } from '../auth/auth';
import { useAuth } from '../auth/useAuth';
import { APP_CONFIG } from '../config';
import { useTitle } from '../hooks/useTitle';

function Gate({ children }: { children: ReactNode }) {
  return (
    <div className="gate">
      <div className="gate-card">
        <div className="brand brand-lg">
          <span className="brand-mark" aria-hidden="true" />
          AEGER
        </div>
        {children}
      </div>
    </div>
  );
}

/** Compte authentifié sans le rôle de realm « medecin » (ex. analyste). */
export function AccessDenied() {
  useTitle('Accès refusé');
  const username = useAuth((s) => s.profile?.username);
  return (
    <Gate>
      <h1>Accès réservé aux médecins</h1>
      <p>
        Le compte <strong>{username}</strong> est bien authentifié, mais il ne possède pas le rôle «&nbsp;
        {APP_CONFIG.requiredRole}&nbsp;» requis pour consulter les alertes et les dossiers patients.
      </p>
      <p className="muted small">Les analystes consultent les indicateurs dans Grafana.</p>
      <button type="button" className="btn btn-primary" onClick={() => auth.logout()}>
        Se déconnecter / changer de compte
      </button>
    </Gate>
  );
}

/** Keycloak injoignable ou retour de connexion en erreur. */
export function AuthError() {
  useTitle('Connexion impossible');
  const message = useAuth((s) => s.initError);
  // Repart d'une URL propre (sans paramètres de retour OIDC dans le fragment).
  const retry = () => window.location.assign(`${window.location.origin}${window.location.pathname}`);
  return (
    <Gate>
      <h1>Connexion impossible</h1>
      <p>Le service d'authentification (Keycloak) est injoignable ou a refusé la connexion.</p>
      <p className="muted small">{message}</p>
      <button type="button" className="btn btn-primary" onClick={retry}>
        Réessayer
      </button>
    </Gate>
  );
}
