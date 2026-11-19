import Keycloak, { type KeycloakTokenParsed } from 'keycloak-js';
import { APP_CONFIG } from '../config';
import { createStore } from '../lib/store';

export interface UserProfile {
  username: string;
  displayName: string;
  email?: string;
  roles: string[];
}

export interface AuthState {
  status: 'initialising' | 'ready' | 'error';
  initError: string | null;
  profile: UserProfile | null;
  isMedecin: boolean;
  /** Problème de session non résolu par une redirection automatique (garde anti-boucle). */
  sessionProblem: string | null;
}

/** Levée quand le jeton ne peut plus être rafraîchi (session Keycloak terminée). */
export class SessionExpiredError extends Error {
  constructor() {
    super('Session expirée');
    this.name = 'SessionExpiredError';
  }
}

const RELOGIN_KEY = 'aeger.relogin-at';
/** Pas de nouvelle redirection automatique vers Keycloak moins de 60 s après la précédente. */
const RELOGIN_GUARD_MS = 60_000;

/**
 * Authentification Keycloak (OIDC, Authorization Code + PKCE S256) via keycloak-js 26.
 * Aucune iframe (`checkLoginIframe: false`, pas de silent-check-sso : cookies tiers) ;
 * le jeton est rafraîchi en tâche de fond avant expiration.
 */
class AuthService {
  private kc?: Keycloak;
  private refreshTimer?: ReturnType<typeof setInterval>;

  readonly store = createStore<AuthState>({
    status: 'initialising',
    initError: null,
    profile: null,
    isMedecin: false,
    sessionProblem: null,
  });

  /**
   * À appeler une fois, avant le premier rendu (le fragment de retour OIDC `#state=…&code=…`
   * est consommé puis effacé avant que le routeur ne lise l'URL). Ne rejette jamais.
   */
  async init(): Promise<void> {
    try {
      const kc = new Keycloak({
        url: `${window.location.origin}${APP_CONFIG.keycloak.path}`,
        realm: APP_CONFIG.keycloak.realm,
        clientId: APP_CONFIG.keycloak.clientId,
      });
      kc.onTokenExpired = () => void this.refreshInBackground();
      kc.onAuthRefreshSuccess = () => this.updateProfile();
      this.kc = kc;
      // 'login-required' : redirection vers la page de connexion si besoin (la promesse ne se
      // résout alors jamais, la page est quittée).
      const authenticated = await kc.init({
        onLoad: 'login-required',
        flow: 'standard',
        pkceMethod: 'S256',
        responseMode: 'fragment',
        checkLoginIframe: false,
        enableLogging: false,
      });
      if (!authenticated) {
        await kc.login();
        return;
      }
      this.updateProfile();
      this.store.set({ status: 'ready' });
      this.startRefreshLoop();
    } catch (err) {
      this.store.set({ status: 'error', initError: describeKeycloakError(err) });
    }
  }

  /** Jeton valide encore au moins `minValidity` s (rafraîchi si besoin ; `-1` force le rafraîchissement). */
  async getValidToken(minValidity: number = APP_CONFIG.keycloak.minValiditySeconds): Promise<string> {
    const kc = this.kc;
    if (!kc?.authenticated) {
      throw new SessionExpiredError();
    }
    try {
      await kc.updateToken(minValidity);
    } catch {
      this.relogin('Votre session a expiré.');
      throw new SessionExpiredError();
    }
    if (!kc.token) {
      this.relogin('Votre session a expiré.');
      throw new SessionExpiredError();
    }
    return kc.token;
  }

  /** Réponse 401 d'une API : la session n'est plus acceptée, on se reconnecte. */
  handleUnauthorized(): void {
    this.relogin('Le serveur a refusé votre session (401).');
  }

  /** Redirection vers Keycloak, avec garde anti-boucle (ex. émetteur du jeton refusé par les API). */
  relogin(reason: string): void {
    const last = Number(sessionStorage.getItem(RELOGIN_KEY) ?? '0');
    if (Date.now() - last < RELOGIN_GUARD_MS) {
      this.store.set({ sessionProblem: reason });
      return;
    }
    this.login();
  }

  login(): void {
    sessionStorage.setItem(RELOGIN_KEY, String(Date.now()));
    void this.kc?.login();
  }

  logout(): void {
    this.stopRefreshLoop();
    sessionStorage.removeItem(RELOGIN_KEY);
    void this.kc?.logout({ redirectUri: `${window.location.origin}/` });
  }

  private startRefreshLoop(): void {
    this.stopRefreshLoop();
    this.refreshTimer = setInterval(() => void this.refreshInBackground(), APP_CONFIG.keycloak.refreshCheckIntervalMs);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') {
        void this.refreshInBackground();
      }
    });
  }

  private stopRefreshLoop(): void {
    if (this.refreshTimer !== undefined) {
      clearInterval(this.refreshTimer);
      this.refreshTimer = undefined;
    }
  }

  private async refreshInBackground(): Promise<void> {
    try {
      await this.getValidToken();
    } catch {
      // relogin() a déjà été déclenché par getValidToken().
    }
  }

  private updateProfile(): void {
    const t: KeycloakTokenParsed | undefined = this.kc?.tokenParsed;
    if (!t) {
      return;
    }
    const username = asString(t['preferred_username']) ?? asString(t.sub) ?? '';
    const fullName = [asString(t['given_name']), asString(t['family_name'])].filter(Boolean).join(' ');
    const roles = t.realm_access?.roles ?? [];
    this.store.set({
      profile: {
        username,
        displayName: asString(t['name']) ?? (fullName || username),
        email: asString(t['email']),
        roles,
      },
      isMedecin: roles.includes(APP_CONFIG.requiredRole),
    });
  }
}

function asString(v: unknown): string | undefined {
  return typeof v === 'string' && v.length > 0 ? v : undefined;
}

function describeKeycloakError(err: unknown): string {
  if (err instanceof Error) {
    return err.message;
  }
  if (typeof err === 'object' && err !== null) {
    const e = err as { error?: unknown; error_description?: unknown };
    const parts = [e.error, e.error_description].filter((p) => typeof p === 'string');
    if (parts.length > 0) {
      return parts.join(' : ');
    }
  }
  return 'Erreur inconnue';
}

/** Instance unique (initialisée par main.tsx avant le premier rendu). */
export const auth = new AuthService();
