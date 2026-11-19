import { useSyncExternalStore } from 'react';

/** Horloge partagée rafraîchie chaque seconde (« il y a 12 s », clignotement des nouvelles alertes). */
let now = Date.now();
let timer: ReturnType<typeof setInterval> | undefined;
const listeners = new Set<() => void>();

function subscribe(listener: () => void): () => void {
  listeners.add(listener);
  if (timer === undefined) {
    now = Date.now();
    timer = setInterval(() => {
      now = Date.now();
      listeners.forEach((l) => l());
    }, 1000);
  }
  return () => {
    listeners.delete(listener);
    if (listeners.size === 0 && timer !== undefined) {
      clearInterval(timer);
      timer = undefined;
    }
  };
}

export function useNow(): number {
  return useSyncExternalStore(subscribe, () => now);
}
