import { useEffect } from 'react';

/** Titre de l'onglet : « <page> · AEGER ». */
export function useTitle(title: string): void {
  useEffect(() => {
    document.title = `${title} · AEGER`;
  }, [title]);
}
