/**
 * Constantes d'exécution de l'application médecin AEGER.
 *
 * Toutes les URL sont relatives à l'origine courante (application servie par NGINX à la racine `/`,
 * Keycloak sous `/auth`, API sous `/api/...`, WebSocket sous `/ws/...`) : un seul build pour tous
 * les environnements. Référence : Services/CONTRAT.md (§ 4 seuils, § 5 API, § 6 URL, § 7 Keycloak).
 */
export const APP_CONFIG = {
  keycloak: {
    /** Chemin public de Keycloak derrière NGINX ; URL complète : `${location.origin}${path}`. */
    path: '/auth',
    realm: 'aeger',
    clientId: 'aeger-web',
    /** Le jeton est rafraîchi s'il expire dans moins de N secondes. */
    minValiditySeconds: 60,
    /** Période de vérification du jeton (rafraîchissement silencieux avant expiration). */
    refreshCheckIntervalMs: 20_000,
  },
  /** Rôle de realm exigé pour utiliser l'application. */
  requiredRole: 'medecin',
  api: {
    recentAlerts: '/api/alerts/recent',
    patients: '/api/vitals/patients',
    history: (patientId: string): string =>
      `/api/vitals/patients/${encodeURIComponent(patientId)}/history`,
    /** Délai maximal d'une requête REST. */
    timeoutMs: 15_000,
  },
  ws: {
    path: '/ws/alerts',
    initialBackoffMs: 1_000,
    maxBackoffMs: 30_000,
    /** Une connexion restée ouverte au moins ce temps remet le backoff à zéro. */
    stableAfterMs: 10_000,
  },
  alerts: {
    /** Alertes conservées en mémoire pendant la session (liste + fiche patient). */
    maxStored: 1_000,
    /** Alertes affichées sur l'écran « Alertes en direct ». */
    maxDisplayed: 200,
    /** Promesse fonctionnelle : alerte affichée moins de 5 s après la mesure. */
    latencyTargetMs: 5_000,
    /** Durée d'affichage du bandeau « nouvelle alerte critique ». */
    criticalBannerMs: 8_000,
  },
  patients: {
    pageSize: 50,
    maxLimit: 500,
    searchDebounceMs: 300,
  },
  history: {
    defaultHours: 24,
    periods: [
      { hours: 1, label: '1 h' },
      { hours: 24, label: '24 h' },
      { hours: 168, label: '7 jours' },
    ],
    /** Rafraîchissement automatique de la fiche patient (onglet visible uniquement). */
    autoRefreshMs: 30_000,
  },
} as const;
