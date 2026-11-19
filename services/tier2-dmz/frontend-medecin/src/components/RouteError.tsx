import { useRouteError } from 'react-router';

/**
 * Erreur inattendue pendant une navigation (ex. morceau JavaScript introuvable après un
 * redéploiement) : proposer de recharger la page.
 */
export function RouteError() {
  const error = useRouteError();
  console.error(error);
  return (
    <div className="gate">
      <div className="gate-card">
        <div className="brand brand-lg">
          <span className="brand-mark" aria-hidden="true" />
          AEGER
        </div>
        <h1>Une erreur est survenue</h1>
        <p>L'écran demandé n'a pas pu être affiché. L'application a peut-être été mise à jour.</p>
        <button type="button" className="btn btn-primary" onClick={() => window.location.reload()}>
          Recharger la page
        </button>
      </div>
    </div>
  );
}
