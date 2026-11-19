package aeger.notification;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/** Déclare le point d'accès WebSocket /ws/alerts. */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AlertsWebSocketHandler handler;
    private final String[] allowedOrigins;

    /**
     * Origines autorisées (en-tête Origin du navigateur) : "*" par défaut, car l'accès est déjà
     * protégé par le mTLS (seul NGINX peut se connecter) et par le jeton. Variable WS_ALLOWED_ORIGINS
     * pour restreindre, par exemple https://192.168.56.10.
     */
    public WebSocketConfig(AlertsWebSocketHandler handler, @Value("${aeger.ws.allowed-origins}") String allowedOrigins) {
        this.handler = handler;
        this.allowedOrigins = allowedOrigins.split("\\s*,\\s*");
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws/alerts").setAllowedOriginPatterns(allowedOrigins);
    }
}
