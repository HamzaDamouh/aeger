package aeger.ingestion;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;

/**
 * Producteur Kafka (topic vitals.raw, clé = patient_id, acks=all, idempotence activée).
 * <p>
 * Démarrage tolérant : un thread essaie de créer le producteur et de lire les métadonnées
 * du topic toutes les 5 s jusqu'à réussir. Tant que Kafka n'est pas joignable,
 * {@link #isReady()} renvoie false (POST /measures répond 503 et MQTT n'est pas encore connecté).
 */
@Component
public class KafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaPublisher.class);

    private final String bootstrap;
    private final String topic;
    private final Properties props = new Properties();
    private final CountDownLatch ready = new CountDownLatch(1);
    private volatile KafkaProducer<String, String> producer;
    private volatile boolean running = true;

    public KafkaPublisher(@Value("${aeger.kafka.bootstrap}") String bootstrap,
                          @Value("${aeger.kafka.topic}") String topic,
                          @Value("${aeger.secrets-dir}") String secretsDir,
                          @Value("${aeger.service-name}") String serviceName) {
        this.bootstrap = bootstrap;
        this.topic = topic;
        props.put("bootstrap.servers", bootstrap);
        props.put("client.id", serviceName);
        // mTLS (contrat § 8) : clé + certificat dans <service>.pem, CA dans ca.pem
        props.put("security.protocol", "SSL");
        props.put("ssl.keystore.type", "PEM");
        props.put("ssl.keystore.location", secretsDir + "/" + serviceName + ".pem");
        props.put("ssl.truststore.type", "PEM");
        props.put("ssl.truststore.location", secretsDir + "/ca.pem");
        props.put("ssl.endpoint.identification.algorithm", "https");
        // fiabilité
        props.put("acks", "all");
        props.put("enable.idempotence", "true");
        props.put("linger.ms", "5");
        props.put("delivery.timeout.ms", "120000");
        props.put("max.block.ms", "5000");          // ne jamais bloquer longtemps un thread HTTP ou MQTT
        props.put("buffer.memory", String.valueOf(16 * 1024 * 1024));
        props.put("reconnect.backoff.ms", "1000");
        props.put("reconnect.backoff.max.ms", "10000");
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
    }

    @PostConstruct
    void start() {
        Thread t = new Thread(this::connectLoop, "kafka-init");
        t.setDaemon(true);
        t.start();
    }

    private void connectLoop() {
        while (running) {
            try {
                if (producer == null) {
                    producer = new KafkaProducer<>(props);
                }
                int partitions = producer.partitionsFor(topic).size();
                log.info("Kafka joignable ({}) : topic {} ({} partitions)", bootstrap, topic, partitions);
                ready.countDown();
                return;
            } catch (Exception e) {
                log.warn("Kafka {} pas encore joignable ({}) - nouvel essai dans 5 s", bootstrap, rootMessage(e));
                sleep(5000);
            }
        }
    }

    public boolean isReady() {
        return ready.getCount() == 0;
    }

    public void awaitReady() throws InterruptedException {
        ready.await();
    }

    /** Envoi asynchrone ; le callback est appelé à l'accusé de réception (ou à l'échec). */
    public Future<RecordMetadata> send(String key, String value, Callback callback) {
        return producer.send(new ProducerRecord<>(topic, key, value), callback);
    }

    @PreDestroy
    void close() {
        running = false;
        KafkaProducer<String, String> p = producer;
        if (p != null) {
            p.close(Duration.ofSeconds(5));
        }
    }

    static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t == e ? e.toString() : e.getMessage() + " / " + t;
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
