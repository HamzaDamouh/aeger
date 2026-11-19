package aeger.notification;

import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;

import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Cœur de notification : les 100 dernières alertes (mémoire) et les sessions WebSocket des médecins.
 * <p>
 * Envoi asynchrone (2 threads dédiés) : la requête POST /internal/alerts n'attend jamais un navigateur.
 * Chaque session est enveloppée dans un {@link ConcurrentWebSocketSessionDecorator} (envois
 * sérialisés, tampon de 256 Ko, 5 s maxi) : une session lente ou fermée est coupée sans
 * bloquer les autres. Un ping est envoyé toutes les 30 s (garde la connexion ouverte à travers NGINX).
 * <p>
 * Planification par un ScheduledExecutorService propre (et non @Scheduled) : avec @EnableWebSocket,
 * Spring déclare son propre TaskScheduler et Spring Boot ne crée plus le sien.
 */
@Component
public class AlertHub {

    private static final Logger log = LoggerFactory.getLogger(AlertHub.class);
    private static final int MAX_RECENT = 100;
    private static final int SEND_TIME_LIMIT_MS = 5000;
    private static final int BUFFER_LIMIT_BYTES = 256 * 1024;

    private record Entry(String alertId, String json) {
    }

    private final Deque<Entry> recent = new ArrayDeque<>(); // la plus récente en tête
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ExecutorService sender = Executors.newFixedThreadPool(2, named("ws-send"));
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(named("ws-timer"));

    // statistiques
    private final LongAdder received = new LongAdder();
    private final LongAdder duplicates = new LongAdder();
    private final LongAdder sent = new LongAdder();
    private final LongAdder failures = new LongAdder();

    public AlertHub() {
        timer.scheduleAtFixedRate(this::pingAll, 30, 30, TimeUnit.SECONDS);
        timer.scheduleAtFixedRate(this::logThroughput, 30, 30, TimeUnit.SECONDS);
    }

    /** Enregistre l'alerte et la diffuse ; false si alert_id déjà reçu (doublon ignoré). */
    public boolean publish(String alertId, String json) {
        received.increment();
        synchronized (recent) {
            if (alertId != null && recent.stream().anyMatch(e -> alertId.equals(e.alertId()))) {
                duplicates.increment();
                return false;
            }
            recent.addFirst(new Entry(alertId, json));
            while (recent.size() > MAX_RECENT) {
                recent.removeLast();
            }
        }
        TextMessage message = new TextMessage(json);
        for (WebSocketSession s : sessions.values()) {
            sender.execute(() -> send(s, message));
        }
        return true;
    }

    /** Les 100 dernières alertes, la plus récente en premier (JSON déjà sérialisé). */
    public List<String> recentNewestFirst() {
        synchronized (recent) {
            List<String> list = new ArrayList<>(recent.size());
            recent.forEach(e -> list.add(e.json()));
            return list;
        }
    }

    public void register(WebSocketSession session) {
        sessions.put(session.getId(),
                new ConcurrentWebSocketSessionDecorator(session, SEND_TIME_LIMIT_MS, BUFFER_LIMIT_BYTES));
    }

    public void unregister(WebSocketSession session) {
        sessions.remove(session.getId());
    }

    public int sessionCount() {
        return sessions.size();
    }

    private void send(WebSocketSession s, WebSocketMessage<?> message) {
        try {
            if (s.isOpen()) {
                s.sendMessage(message);
                if (message instanceof TextMessage) {
                    sent.increment();
                }
            } else {
                sessions.remove(s.getId());
            }
        } catch (Exception e) {
            // session lente (limite dépassée) ou fermée : on la coupe
            failures.increment();
            sessions.remove(s.getId());
            log.info("Session WebSocket {} coupée : {}", s.getId(), e.toString());
            try {
                s.close(CloseStatus.SESSION_NOT_RELIABLE);
            } catch (Exception ignored) {
                // déjà fermée
            }
        }
    }

    private void pingAll() {
        PingMessage ping = new PingMessage(ByteBuffer.wrap(new byte[]{'a', 'e', 'g', 'e', 'r'}));
        for (WebSocketSession s : sessions.values()) {
            sender.execute(() -> send(s, ping));
        }
    }

    private void logThroughput() {
        log.info("Débit 30 s : alertes reçues={} | doublons ignorés={} | messages WebSocket envoyés={} | échecs={} | médecins connectés={}",
                received.sumThenReset(), duplicates.sumThenReset(), sent.sumThenReset(), failures.sumThenReset(),
                sessions.size());
    }

    @PreDestroy
    void stop() {
        timer.shutdownNow();
        sender.shutdown();
    }

    private static java.util.concurrent.ThreadFactory named(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> {
            Thread t = new Thread(r, prefix + "-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        };
    }
}
