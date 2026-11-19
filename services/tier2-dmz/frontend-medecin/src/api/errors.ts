import { SessionExpiredError } from '../auth/auth';
import { HttpError, NetworkError, RequestTimeoutError } from './http';

export type UiErrorKind = 'auth' | 'forbidden' | 'notfound' | 'network' | 'server' | 'other';

export interface UiError {
  kind: UiErrorKind;
  message: string;
  /** Afficher un bouton « Réessayer ». */
  retryable: boolean;
}

/** Traduit une erreur de requête en message affichable. */
export function toUiError(err: unknown): UiError {
  if (err instanceof SessionExpiredError) {
    return { kind: 'auth', message: 'Session expirée : redirection vers la page de connexion…', retryable: false };
  }
  if (err instanceof RequestTimeoutError) {
    return { kind: 'network', message: 'Le serveur ne répond pas (délai dépassé).', retryable: true };
  }
  if (err instanceof NetworkError) {
    return { kind: 'network', message: 'Serveur injoignable : vérifiez la connexion réseau.', retryable: true };
  }
  if (err instanceof HttpError) {
    const s = err.status;
    if (s === 401) {
      return { kind: 'auth', message: 'Session expirée ou refusée par le serveur : reconnexion en cours…', retryable: true };
    }
    if (s === 403) {
      return {
        kind: 'forbidden',
        message: "Accès refusé : votre compte n'a pas les droits requis (rôle « médecin ») pour ce service.",
        retryable: false,
      };
    }
    if (s === 404) return { kind: 'notfound', message: 'Ressource introuvable (404).', retryable: true };
    if (s >= 500) return { kind: 'server', message: `Service momentanément indisponible (erreur ${s}).`, retryable: true };
    return { kind: 'other', message: `Requête refusée (erreur ${s}).`, retryable: true };
  }
  return { kind: 'other', message: 'Erreur inattendue (réponse illisible).', retryable: true };
}
