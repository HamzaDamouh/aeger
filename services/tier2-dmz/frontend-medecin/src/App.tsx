import { RouterProvider } from 'react-router/dom';
import { useAuth } from './auth/useAuth';
import { AccessDenied, AuthError } from './pages/AccessPages';
import type { AppRouter } from './router';

/**
 * Aiguillage selon l'authentification : Keycloak en erreur, compte sans le rôle « medecin »,
 * ou application (routes) pour un médecin.
 */
export function App({ router }: { router: AppRouter }) {
  const status = useAuth((s) => s.status);
  const isMedecin = useAuth((s) => s.isMedecin);

  if (status === 'error') return <AuthError />;
  if (status !== 'ready') return null;
  if (!isMedecin) return <AccessDenied />;
  return <RouterProvider router={router} />;
}
