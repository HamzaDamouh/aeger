package aeger.alerting;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Envoi des alertes à notification : POST JSON sur NOTIFICATION_URL, en mTLS avec le
 * certificat du service (SSL bundle "service"), vérification du nom d'hôte active
 * (comportement par défaut du HttpClient du JDK).
 * <p>
 * L'envoi est découplé de la lecture Kafka par une file bornée (1 000 alertes) et 2 threads ;
 * chaque alerte est tentée 3 fois (1 s puis 2 s d'attente) puis abandonnée (journal ERROR).
 */
@Component
public class AlertSender {

    private static final Logger log = LoggerFactory.getLogger(AlertSender.class);
    private static final int ATTEMPTS = 3;

    private record Pending(String json, String summary) {
    }

    private final BlockingQueue<Pending> queue = new ArrayBlockingQueue<>(1000);
    private final URI uri;
    private final SslBundles sslBundles;
    private final Stats stats;
    private HttpClient http;
    private volatile boolean running = true;
    private volatile long lastDropLog;

    public AlertSender(@Value("${aeger.notification-url}") String url, SslBundles sslBundles, Stats stats) {
        this.uri = URI.create(url);
        this.sslBundles = sslBundles;
        this.stats = stats;
    }

    @PostConstruct
    void start() {
        http = HttpClient.newBuilder()
                .sslContext(sslBundles.getBundle("service").createSslContext())
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(3))
                .build();
        for (int i = 1; i <= 2; i++) {
            Thread t = new Thread(this::loop, "alert-sender-" + i);
            t.setDaemon(true);
            t.start();
        }
        log.info("Alertes envoyées à {}", uri);
    }

    /** Met l'alerte en file d'envoi (non bloquant). */
    public void submit(String json, String summary) {
        if (!queue.offer(new Pending(json, summary))) {
            stats.dropped.increment();
            long now = System.currentTimeMillis();
            if (now - lastDropLog > 10_000) {
                lastDropLog = now;
                log.error("File d'envoi pleine (notification trop lente ou injoignable) : alerte perdue {}", summary);
            }
        }
    }

    private void loop() {
        while (running) {
            try {
                Pending p = queue.poll(1, TimeUnit.SECONDS);
                if (p != null) {
                    send(p);
                }
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    private void send(Pending p) throws InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(p.json()))
                .build();
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
                if (status / 100 == 2) {
                    stats.sent.increment();
                    log.info("ALERTE envoyée : {}", p.summary());
                    return;
                }
                log.warn("notification a répondu HTTP {} (essai {}/{}) pour {}", status, attempt, ATTEMPTS, p.summary());
                if (status / 100 == 4) {
                    break; // requête refusée (CN, format) : inutile de réessayer
                }
            } catch (IOException e) {
                log.warn("notification injoignable (essai {}/{}) : {}", attempt, ATTEMPTS, e.toString());
            }
            if (attempt < ATTEMPTS) {
                Thread.sleep(attempt * 1000L);
            }
        }
        stats.failed.increment();
        log.error("ALERTE NON transmise : {}", p.summary());
    }

    @PreDestroy
    void stop() {
        running = false;
    }
}
