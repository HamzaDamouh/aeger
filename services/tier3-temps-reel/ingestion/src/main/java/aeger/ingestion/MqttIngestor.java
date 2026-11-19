package aeger.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.eclipse.paho.client.mqttv3.IMqttActionListener;
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Abonné MQTT (bracelets haut risque) : topic aeger/vitals/# en QoS 1, mTLS avec le
 * certificat du service (SSL bundle "service"), vérification du nom d'hôte activée.
 * <p>
 * La connexion n'est ouverte qu'une fois Kafka joignable (sinon les messages seraient perdus) ;
 * la session est persistante (cleanSession=false) : mosquitto garde les messages QoS 1
 * pendant une coupure. Après la première connexion, Paho se reconnecte tout seul.
 */
@Component
public class MqttIngestor implements MqttCallbackExtended {

    private static final Logger log = LoggerFactory.getLogger(MqttIngestor.class);

    private final String url;
    private final String topic;
    private final String clientId;
    private final SslBundles sslBundles;
    private final KafkaPublisher publisher;
    private final IngestionService service;
    private final ObjectMapper mapper;
    private final Stats stats;
    private MqttAsyncClient client;
    private volatile boolean running = true;

    public MqttIngestor(@Value("${aeger.mqtt.url}") String url,
                        @Value("${aeger.mqtt.topic}") String topic,
                        @Value("${aeger.mqtt.client-id}") String clientId,
                        SslBundles sslBundles, KafkaPublisher publisher, IngestionService service,
                        ObjectMapper mapper, Stats stats) {
        this.url = url;
        this.topic = topic;
        this.clientId = clientId;
        this.sslBundles = sslBundles;
        this.publisher = publisher;
        this.service = service;
        this.mapper = mapper;
        this.stats = stats;
    }

    @PostConstruct
    void start() throws MqttException {
        client = new MqttAsyncClient(url, clientId, new MemoryPersistence());
        client.setCallback(this);
        Thread t = new Thread(this::connectLoop, "mqtt-connect");
        t.setDaemon(true);
        t.start();
    }

    private MqttConnectOptions options() {
        MqttConnectOptions o = new MqttConnectOptions();
        o.setMqttVersion(MqttConnectOptions.MQTT_VERSION_3_1_1);
        o.setCleanSession(false);
        o.setAutomaticReconnect(true);
        o.setMaxReconnectDelay(30_000);
        o.setKeepAliveInterval(30);
        o.setConnectionTimeout(10);
        o.setSocketFactory(sslBundles.getBundle("service").createSslContext().getSocketFactory());
        o.setHttpsHostnameVerificationEnabled(true);
        return o;
    }

    /** Première connexion : on réessaie toutes les 5 s (Paho ne le fait qu'après une 1re réussite). */
    private void connectLoop() {
        try {
            publisher.awaitReady();
        } catch (InterruptedException e) {
            return;
        }
        MqttConnectOptions options = options();
        while (running) {
            try {
                log.info("Connexion MQTT à {} (client {})", url, clientId);
                client.connect(options).waitForCompletion();
                return; // connectComplete() s'occupe de l'abonnement
            } catch (MqttException e) {
                log.warn("Connexion MQTT impossible ({}) - nouvel essai dans 5 s", describe(e));
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    @Override
    public void connectComplete(boolean reconnect, String serverUri) {
        log.info("MQTT {} à {} - abonnement à {} (QoS 1)", reconnect ? "reconnecté" : "connecté", serverUri, topic);
        try {
            client.subscribe(topic, 1, null, new IMqttActionListener() {
                @Override
                public void onSuccess(IMqttToken token) {
                    log.info("Abonné à {}", topic);
                }

                @Override
                public void onFailure(IMqttToken token, Throwable e) {
                    log.error("Abonnement MQTT à {} refusé : {}", topic, e.toString());
                }
            });
        } catch (MqttException e) {
            log.error("Abonnement MQTT impossible : {}", describe(e));
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("Connexion MQTT perdue ({}) - reconnexion automatique", cause == null ? "?" : cause.toString());
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // Ne jamais laisser remonter une exception : Paho fermerait la connexion.
        try {
            handle(topic, message.getPayload());
        } catch (Exception e) {
            log.warn("Message MQTT ignoré ({}) : {}", topic, e.toString());
        }
    }

    void handle(String topic, byte[] payload) {
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        String topicBracelet = topic.substring(topic.lastIndexOf('/') + 1);
        JsonNode root;
        try {
            root = mapper.readTree(payload);
        } catch (IOException e) {
            stats.mqttMeasure();
            stats.rejected("JSON MQTT illisible sur " + topic);
            return;
        }
        Iterable<JsonNode> items = root.isArray() ? root : List.of(root);
        for (JsonNode item : items) {
            stats.mqttMeasure();
            // le suffixe du topic doit être le bracelet_id de la mesure
            if (!topicBracelet.equals(item.path("bracelet_id").asText())) {
                stats.rejected("bracelet_id différent du topic " + topic);
                continue;
            }
            service.submit(item, "mqtt", receivedAt);
        }
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // on ne publie rien
    }

    @PreDestroy
    void stop() {
        running = false;
        try {
            if (client.isConnected()) {
                client.disconnect(2000).waitForCompletion(3000);
            }
            client.close();
        } catch (MqttException e) {
            // arrêt : on ignore
        }
    }

    private static String describe(MqttException e) {
        return e.getCause() == null ? e.toString() : e + " / " + e.getCause();
    }
}
