package aeger.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.adapter.NativeWebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

/**
 * WebSocket simple (sans STOMP ni SockJS) sur /ws/alerts : chaque alerte est envoyée au navigateur
 * sous la forme d'un message texte JSON. Le CN nginx et le jeton (access_token) ont déjà été
 * vérifiés par {@link ClientCertFilter} pendant la poignée de main HTTP.
 */
@Component
public class AlertsWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(AlertsWebSocketHandler.class);
    /** Délai maximal d'un envoi bloquant côté Tomcat (défaut 20 s). */
    private static final long BLOCKING_SEND_TIMEOUT_MS = 5000;

    private final AlertHub hub;

    public AlertsWebSocketHandler(AlertHub hub) {
        this.hub = hub;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        if (session instanceof NativeWebSocketSession nativeSession) {
            jakarta.websocket.Session s = nativeSession.getNativeSession(jakarta.websocket.Session.class);
            if (s != null) {
                s.getUserProperties().put("org.apache.tomcat.websocket.BLOCKING_SEND_TIMEOUT", BLOCKING_SEND_TIMEOUT_MS);
            }
        }
        hub.register(session);
        log.info("Médecin connecté (session {}) - {} connecté(s)", session.getId(), hub.sessionCount());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // le navigateur n'a rien à envoyer : messages ignorés
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        hub.unregister(session);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        hub.unregister(session);
        log.info("Médecin déconnecté (session {}, {}) - {} connecté(s)", session.getId(), status.getCode(), hub.sessionCount());
    }
}
