import { useStore } from '../lib/store';
import { auth, type AuthState } from './auth';

/** État d'authentification (sélecteur sur un champ de l'état). */
export function useAuth<S>(selector: (s: AuthState) => S): S {
  return useStore(auth.store, selector);
}
