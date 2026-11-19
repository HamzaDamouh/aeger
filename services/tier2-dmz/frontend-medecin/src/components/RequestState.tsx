import type { UiError } from '../api/errors';
import { auth } from '../auth/auth';

const TITLES: Record<UiError['kind'], string> = {
  auth: 'Session expirée.',
  forbidden: 'Accès refusé.',
  network: 'Problème réseau.',
  server: 'Service indisponible.',
  notfound: 'Introuvable.',
  other: 'Erreur.',
};

interface Props {
  loading?: boolean;
  error?: UiError | null;
  loadingText?: string;
  onRetry?: () => void;
}

/** État d'une requête : chargement, ou erreur avec « Réessayer » / « Se reconnecter ». */
export function RequestState({ loading = false, error = null, loadingText = 'Chargement…', onRetry }: Props) {
  if (error) {
    const cls = error.kind === 'forbidden' ? 'notice notice-warn' : 'notice notice-error';
    return (
      <div className={cls} role="alert">
        <span className="notice-icon" aria-hidden="true">!</span>
        <div className="notice-body">
          <strong>{TITLES[error.kind]}</strong>
          <span>{error.message}</span>
        </div>
        {error.kind === 'auth' ? (
          <button type="button" className="btn" onClick={() => auth.login()}>Se reconnecter</button>
        ) : error.retryable && onRetry ? (
          <button type="button" className="btn" onClick={onRetry}>Réessayer</button>
        ) : null}
      </div>
    );
  }
  if (loading) {
    return (
      <div className="loading" role="status">
        <span className="spinner" aria-hidden="true" />
        {loadingText}
      </div>
    );
  }
  return null;
}
