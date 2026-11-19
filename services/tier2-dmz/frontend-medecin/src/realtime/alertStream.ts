import { fetchRecentAlerts } from '../api/aeger';
import { toUiError, type UiError } from '../api/errors';
import { auth } from '../auth/auth';
import { APP_CONFIG } from '../config';
import { toReceived } from '../domain/alertRow';
import { normalizeAlert, type ReceivedAlert } from '../domain/models';
import { createStore } from '../lib/store';

export type StreamStatus = 'idle' | 'connecting' | 'connected' | 'reconnecting' | 'stopped';

export interface AlertStreamState {
  status: StreamStatus;
  nextRetryAt: number | null;
  /** Alertes de la session, dédoublonnées par alert_id, plus récentes d'abord. */
  alerts: ReceivedAlert[];
  /** Alertes distinctes reçues par le WebSocket depuis l'ouverture de la page. */
  liveCount: number;
  recentLoading: boolean;
  recentError: UiError | null;
  recentLoadedAt: number | null;
  /** Dernière alerte critique reçue en direct (bandeau), remise à null après quelques secondes. */
  criticalBanner: ReceivedAlert | null;
}

/** Codes de fermeture signalant un jeton refusé : on force alors un nouveau jeton. */
const AUTH_CLOSE_CODES = new Set([1008, 4001, 4401, 4403]);

/**
 * Flux d'alertes temps réel : WebSocket `wss://<hôte>/ws/alerts?access_token=<JWT>`, reconnexion
 * automatique (backoff exponentiel + aléa, jeton frais à chaque tentative), plus
 * `GET /api/alerts/recent` au démarrage et après chaque reconnexion (rattrapage de la coupure).
 */
class AlertStream {
  private ws?: WebSocket;
  private started = false;
  private everConnected = false;
  private attempt = 0;
  private openedAt = 0;
  private retryTimer?: ReturnType<typeof setTimeout>;
  private bannerTimer?: ReturnType<typeof setTimeout>;
  private recentAbort?: AbortController;

  readonly store = createStore<AlertStreamState>({
    status: 'idle',
    nextRetryAt: null,
    alerts: [],
    liveCount: 0,
    recentLoading: false,
    recentError: null,
    recentLoadedAt: null,
    criticalBanner: null,
  });

  start(): void {
    if (this.started) return;
    this.started = true;
    window.addEventListener('online', this.onOnline);
    this.loadRecent();
    void this.connect(false);
  }

  stop(): void {
    this.started = false;
    window.removeEventListener('online', this.onOnline);
    this.clearRetry();
    this.recentAbort?.abort();
    const ws = this.ws;
    this.ws = undefined;
    ws?.close(1000, 'logout');
    this.store.set({ status: 'stopped' });
  }

  /** Recharge les 100 dernières alertes (démarrage, reconnexion, ou bouton « Recharger »). */
  loadRecent = (): void => {
    this.recentAbort?.abort();
    const ctrl = new AbortController();
    this.recentAbort = ctrl;
    this.store.set({ recentLoading: true, recentError: null });
    fetchRecentAlerts(ctrl.signal).then(
      (list) => {
        const now = Date.now();
        this.merge(list.map((a) => toReceived(a, 'history', now)));
        this.store.set({ recentLoading: false, recentLoadedAt: now });
      },
      (err: unknown) => {
        if (ctrl.signal.aborted) return;
        this.store.set({ recentLoading: false, recentError: toUiError(err) });
      },
    );
  };

  /** Reconnexion immédiate (clic sur l'indicateur, retour du réseau). */
  reconnectNow = (): void => {
    if (!this.started || this.store.get().status === 'connected') return;
    this.clearRetry();
    this.attempt = 0;
    void this.connect(false);
  };

  dismissBanner = (): void => this.store.set({ criticalBanner: null });

  private readonly onOnline = (): void => this.reconnectNow();

  private async connect(forceRefresh: boolean): Promise<void> {
    if (!this.started) return;
    this.store.set({
      nextRetryAt: null,
      status: this.everConnected || this.attempt > 0 ? 'reconnecting' : 'connecting',
    });

    let token: string;
    try {
      // Jeton frais à chaque tentative : rafraîchi s'il expire bientôt, ou forcé (-1) si le
      // serveur a refusé le précédent.
      token = await auth.getValidToken(forceRefresh ? -1 : APP_CONFIG.keycloak.minValiditySeconds);
    } catch {
      this.scheduleReconnect(false);
      return;
    }
    if (!this.started) return;

    const scheme = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
    const url = `${scheme}//${window.location.host}${APP_CONFIG.ws.path}?access_token=${encodeURIComponent(token)}`;
    let ws: WebSocket;
    try {
      ws = new WebSocket(url);
    } catch {
      this.scheduleReconnect(false);
      return;
    }
    this.ws = ws;

    ws.onopen = () => {
      if (this.ws !== ws) return;
      const isReconnect = this.everConnected;
      this.everConnected = true;
      this.openedAt = Date.now();
      this.store.set({ status: 'connected' });
      if (isReconnect) {
        this.loadRecent(); // rattrape les alertes émises pendant la coupure
      }
    };
    ws.onmessage = (ev: MessageEvent) => {
      if (this.ws === ws) this.handleMessage(ev.data, Date.now());
    };
    ws.onclose = (ev: CloseEvent) => {
      if (this.ws !== ws) return;
      this.ws = undefined;
      if (!this.started) return;
      if (this.openedAt > 0 && Date.now() - this.openedAt >= APP_CONFIG.ws.stableAfterMs) {
        this.attempt = 0;
      }
      this.openedAt = 0;
      this.scheduleReconnect(AUTH_CLOSE_CODES.has(ev.code));
    };
  }

  private scheduleReconnect(forceRefresh: boolean): void {
    if (!this.started) return;
    this.clearRetry();
    const base = Math.min(APP_CONFIG.ws.maxBackoffMs, APP_CONFIG.ws.initialBackoffMs * 2 ** this.attempt);
    const delay = Math.round(base / 2 + Math.random() * (base / 2));
    this.attempt++;
    this.store.set({ status: 'reconnecting', nextRetryAt: Date.now() + delay });
    this.retryTimer = setTimeout(() => void this.connect(forceRefresh), delay);
  }

  private clearRetry(): void {
    if (this.retryTimer !== undefined) {
      clearTimeout(this.retryTimer);
      this.retryTimer = undefined;
    }
    this.store.set({ nextRetryAt: null });
  }

  private handleMessage(data: unknown, receivedAt: number): void {
    if (typeof data !== 'string') return;
    let parsed: unknown;
    try {
      parsed = JSON.parse(data);
    } catch {
      return; // message non JSON (ex. « pong ») : ignoré
    }
    const items = Array.isArray(parsed) ? parsed : [parsed];
    const batch: ReceivedAlert[] = [];
    for (const item of items) {
      const alert = normalizeAlert(item);
      if (alert) batch.push(toReceived(alert, 'live', receivedAt));
    }
    const added = this.merge(batch);
    if (added.length === 0) return;
    this.store.set((s) => ({ liveCount: s.liveCount + added.length }));
    const critical = added.find((a) => a.alert.severity === 'CRITICAL');
    if (critical) {
      this.store.set({ criticalBanner: critical });
      clearTimeout(this.bannerTimer);
      this.bannerTimer = setTimeout(() => this.store.set({ criticalBanner: null }), APP_CONFIG.alerts.criticalBannerMs);
    }
  }

  /** Fusionne en dédoublonnant par alert_id ; renvoie les alertes réellement nouvelles. */
  private merge(incoming: ReceivedAlert[]): ReceivedAlert[] {
    if (incoming.length === 0) return [];
    const byId = new Map(this.store.get().alerts.map((r) => [r.alert.alert_id, r]));
    const added: ReceivedAlert[] = [];
    for (const r of incoming) {
      const existing = byId.get(r.alert.alert_id);
      if (!existing) {
        byId.set(r.alert.alert_id, r);
        if (r.origin === 'live') added.push(r);
      } else if (existing.origin === 'history' && r.origin === 'live') {
        byId.set(r.alert.alert_id, r);
      }
    }
    const alerts = [...byId.values()].sort((a, b) => sortKey(b) - sortKey(a)).slice(0, APP_CONFIG.alerts.maxStored);
    this.store.set({ alerts });
    return added;
  }
}

function sortKey(r: ReceivedAlert): number {
  if (Number.isFinite(r.detectedMs)) return r.detectedMs;
  if (Number.isFinite(r.measureMs)) return r.measureMs;
  return r.receivedAt;
}

/** Instance unique (démarrée par main.tsx pour un utilisateur ayant le rôle médecin). */
export const alertStream = new AlertStream();
