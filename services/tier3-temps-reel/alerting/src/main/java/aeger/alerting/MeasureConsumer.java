package aeger.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * Consommateur Kafka (groupe "alerting", auto.offset.reset=latest, faible latence).
 * Chaque mesure est évaluée par {@link ThresholdRules} ; les alertes partent vers {@link AlertSender}.
 * <p>
 * Ne s'arrête jamais sur une erreur Kafka : nouvel essai toutes les 5 s
 * (y compris si le nom vm-kafka-01 ne se résout pas encore au démarrage).
 */
@Component
public class MeasureConsumer {

    private static final Logger log = LoggerFactory.getLogger(MeasureConsumer.class);

    private final String topic;
    private final String bootstrap;
    private final Properties props = new Properties();
    private final ObjectMapper mapper;
    private final AlertSender sender;
    private final Stats stats;
    private volatile KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;
    private Thread thread;

    public MeasureConsumer(@Value("${aeger.kafka.bootstrap}") String bootstrap,
                           @Value("${aeger.kafka.topic}") String topic,
                           @Value("${aeger.kafka.group-id}") String groupId,
                           @Value("${aeger.secrets-dir}") String secretsDir,
                           @Value("${aeger.service-name}") String serviceName,
                           ObjectMapper mapper, AlertSender sender, Stats stats) {
        this.bootstrap = bootstrap;
        this.topic = topic;
        this.mapper = mapper;
        this.sender = sender;
        this.stats = stats;
        props.put("bootstrap.servers", bootstrap);
        props.put("client.id", serviceName);
        props.put("group.id", groupId);
        // mTLS (contrat § 8)
        props.put("security.protocol", "SSL");
        props.put("ssl.keystore.type", "PEM");
        props.put("ssl.keystore.location", secretsDir + "/" + serviceName + ".pem");
        props.put("ssl.truststore.type", "PEM");
        props.put("ssl.truststore.location", secretsDir + "/ca.pem");
        props.put("ssl.endpoint.identification.algorithm", "https");
        // alerting ne s'intéresse qu'aux mesures récentes
        props.put("auto.offset.reset", "latest");
        props.put("enable.auto.commit", "false");
        // faible latence
        props.put("fetch.min.bytes", "1");
        props.put("fetch.max.wait.ms", "100");
        props.put("max.poll.records", "500");
        props.put("fetch.max.bytes", String.valueOf(4 * 1024 * 1024));
        props.put("max.partition.fetch.bytes", String.valueOf(1024 * 1024));
        props.put("reconnect.backoff.ms", "1000");
        props.put("reconnect.backoff.max.ms", "10000");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
    }

    @PostConstruct
    void start() {
        // thread non démon : il maintient la JVM en vie (pas de serveur web)
        thread = new Thread(this::run, "kafka-consumer");
        thread.start();
    }

    private void run() {
        while (running) {
            try {
                if (consumer == null) {
                    consumer = new KafkaConsumer<>(props);
                    consumer.subscribe(List.of(topic), new RebalanceLogger());
                    log.info("Abonnement Kafka {} au topic {} (groupe {})", bootstrap, topic, props.get("group.id"));
                }
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(1));
                for (ConsumerRecord<String, String> record : records) {
                    process(record.value());
                }
                if (!records.isEmpty()) {
                    consumer.commitAsync();
                }
            } catch (WakeupException e) {
                // arrêt demandé
            } catch (Exception e) {
                log.warn("Erreur Kafka ({}) - nouvel essai dans 5 s", e.toString());
                sleep(5000);
            }
        }
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            try {
                c.commitSync(Duration.ofSeconds(3));
            } catch (Exception e) {
                // arrêt : on ignore
            }
            c.close(Duration.ofSeconds(5));
        }
    }

    void process(String value) {
        stats.measures.increment();
        JsonNode measure;
        try {
            measure = value == null ? null : mapper.readTree(value);
        } catch (Exception e) {
            measure = null;
        }
        if (measure == null || !measure.isObject()) {
            stats.invalid.increment();
            return;
        }
        ThresholdRules.Evaluation eval = ThresholdRules.evaluate(measure);
        if (eval.isAlert()) {
            stats.alerts.increment();
            ObjectNode alert = AlertFactory.build(mapper, measure, eval, Instant.now());
            sender.submit(alert.toString(), AlertFactory.summary(alert));
        }
    }

    @PreDestroy
    void stop() throws InterruptedException {
        running = false;
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.wakeup();
        }
        if (thread != null) {
            thread.join(10_000);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static class RebalanceLogger implements ConsumerRebalanceListener {
        @Override
        public void onPartitionsAssigned(Collection<TopicPartition> partitions) {
            log.info("Partitions attribuées : {}", partitions);
        }

        @Override
        public void onPartitionsRevoked(Collection<TopicPartition> partitions) {
            if (!partitions.isEmpty()) {
                log.info("Partitions retirées : {}", partitions);
            }
        }
    }
}
