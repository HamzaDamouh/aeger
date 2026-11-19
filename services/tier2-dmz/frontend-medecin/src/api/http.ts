import { auth } from '../auth/auth';
import { APP_CONFIG } from '../config';

/** Réponse HTTP non 2xx. */
export class HttpError extends Error {
  readonly status: number;
  constructor(status: number, url: string) {
    super(`HTTP ${status} ${url}`);
    this.name = 'HttpError';
    this.status = status;
  }
}

/** Serveur injoignable (réseau, TLS, CORS…). */
export class NetworkError extends Error {
  constructor() {
    super('Réseau');
    this.name = 'NetworkError';
  }
}

export class RequestTimeoutError extends Error {
  constructor() {
    super('Délai dépassé');
    this.name = 'RequestTimeoutError';
  }
}

export function isAbortError(err: unknown): boolean {
  return err instanceof DOMException && err.name === 'AbortError';
}

/**
 * `GET` JSON sur l'API de la même origine, avec `Authorization: Bearer <jeton>` (jeton rafraîchi
 * si besoin), délai maximal et traitement du 401 (reconnexion Keycloak).
 */
export async function apiGet<T>(
  path: string,
  params?: Record<string, string | number>,
  signal?: AbortSignal,
): Promise<T> {
  const url = new URL(path, window.location.origin);
  for (const [k, v] of Object.entries(params ?? {})) {
    url.searchParams.set(k, String(v));
  }
  const token = await auth.getValidToken();
  signal?.throwIfAborted();

  const timeout = AbortSignal.timeout(APP_CONFIG.api.timeoutMs);
  let res: Response;
  try {
    res = await fetch(url, {
      headers: { Authorization: `Bearer ${token}`, Accept: 'application/json' },
      cache: 'no-store',
      signal: signal ? AbortSignal.any([signal, timeout]) : timeout,
    });
  } catch (err) {
    if (signal?.aborted) throw err;
    if (timeout.aborted) throw new RequestTimeoutError();
    throw new NetworkError();
  }
  if (res.status === 401) {
    auth.handleUnauthorized();
  }
  if (!res.ok) {
    throw new HttpError(res.status, url.pathname);
  }
  return (await res.json()) as T;
}
