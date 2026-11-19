import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { App } from './App';
import { auth } from './auth/auth';
import { alertStream } from './realtime/alertStream';
import { createAppRouter } from './router';
import './styles.css';

const container = document.getElementById('root');
if (!container) {
  throw new Error('#root introuvable');
}

// 1. Keycloak d'abord (redirection vers la connexion si besoin ; le retour OIDC est consommé et
//    effacé de l'URL). 2. Flux d'alertes si le compte a le rôle « medecin ». 3. Rendu React.
void auth.init().then(() => {
  const state = auth.store.get();
  if (state.status === 'ready' && state.isMedecin) {
    alertStream.start();
  }
  createRoot(container).render(
    <StrictMode>
      <App router={createAppRouter()} />
    </StrictMode>,
  );
});
