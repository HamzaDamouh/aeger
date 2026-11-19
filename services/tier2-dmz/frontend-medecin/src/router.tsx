import { Navigate, createBrowserRouter } from 'react-router';
import { Layout } from './components/Layout';
import { RouteError } from './components/RouteError';
import { AlertsPage } from './pages/AlertsPage';
import { PatientsPage } from './pages/PatientsPage';

export type AppRouter = ReturnType<typeof createBrowserRouter>;

/**
 * Routes de l'application. À créer APRÈS l'initialisation de Keycloak (le fragment de retour OIDC
 * doit déjà être effacé de l'URL quand le routeur la lit).
 * La fiche patient (et Chart.js) est chargée à la demande.
 */
export function createAppRouter(): AppRouter {
  return createBrowserRouter([
    {
      path: '/',
      element: <Layout />,
      errorElement: <RouteError />,
      // Affiché pendant le chargement initial d'une route à la demande (ex. lien direct vers une fiche).
      hydrateFallbackElement: (
        <div className="boot-splash" role="status">
          <span className="spinner" aria-hidden="true" />
          Chargement…
        </div>
      ),
      children: [
        { index: true, element: <AlertsPage /> },
        { path: 'patients', element: <PatientsPage /> },
        {
          path: 'patients/:id',
          lazy: async () => ({ Component: (await import('./pages/PatientPage')).PatientPage }),
        },
        { path: '*', element: <Navigate to="/" replace /> },
      ],
    },
  ]);
}
