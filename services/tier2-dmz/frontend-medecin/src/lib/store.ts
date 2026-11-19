import { useSyncExternalStore } from 'react';

/**
 * Petit magasin d'état observable (hors React) : l'authentification et le flux d'alertes vivent
 * en dehors de l'arbre de composants (initialisés avant le premier rendu) et sont lus par les
 * composants via `useStore` (useSyncExternalStore).
 */
export interface Store<T> {
  get(): T;
  set(patch: Partial<T> | ((s: T) => Partial<T>)): void;
  subscribe(listener: () => void): () => void;
}

export function createStore<T extends object>(initial: T): Store<T> {
  let state = initial;
  const listeners = new Set<() => void>();
  return {
    get: () => state,
    set(patch) {
      const p = typeof patch === 'function' ? patch(state) : patch;
      state = { ...state, ...p };
      listeners.forEach((l) => l());
    },
    subscribe(listener) {
      listeners.add(listener);
      return () => listeners.delete(listener);
    },
  };
}

/** Lit une partie de l'état ; le sélecteur doit renvoyer une valeur stable (champ de l'état). */
export function useStore<T, S>(store: Store<T>, selector: (s: T) => S): S {
  return useSyncExternalStore(store.subscribe, () => selector(store.get()));
}
