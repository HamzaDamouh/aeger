package aeger.vitals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Properties;

/**
 * Consommateur Kafka (groupe "vitals", auto.offset.reset=earliest) qui écrit les mesures dans la
 * table measures par lots JDBC : INSERT ... ON CONFLICT DO NOTHING (les doublons sont ignorés grâce à
 * la contrainte UNIQUE (bracelet_id, seq, time)). Les offsets ne sont validés qu'APRÈS l'insertion.
 * <p>
 * Si la base est injoignable : on revient au début du lot (seek) et on réessaie toutes les 5 s,
 * sans perte. Si Kafka est injoignable : nouvel essai toutes les 5 s. Le service ne s'arrête jamais.
 */
@Component
public class MeasureWriter {

    private static final Logger log = LoggerFactory.getLogger(MeasureWriter.class);

    static final String INSERT = "INSERT INTO measures (time, patient_id, bracelet_id, type, seq, heart_rate, spo2,"
            + " temperature, systolic, diastolic, anomaly) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT DO NOTHING";

    /** Une ligne de la table measures. */
    record Row(Instant time, String patientId, String braceletId, String type, long seq, Integer heartRate,
               Integer spo2, Float temperature, Integer systolic, Integer diastolic, boolean anomaly) {
    }

    private final DataSource writer;
    private final ObjectMapper mapper;
    private final Stats stats;
    private final String topic;
    private final String bootstrap;
    private final Properties props = new Properties();
    private volatile KafkaConsumer<String, String> consumer;
    private volatile boolean running = true;
    private Thread thread;

    public MeasureWriter(@Qualifier("writerDataSource") DataSource writer, ObjectMapper mapper, Stats stats,
                         @Value("${aeger.kafka.bootstrap}") String bootstrap,
                         @Value("${aeger.kafka.topic}") String topic,
                         @Value("${aeger.kafka.group-id}") String groupId,
                         @Value("${aeger.secrets-dir}") String secretsDir,
                         @Value("${aeger.service-name}") String serviceName) {
        this.writer = writer;
        this.mapper = mapper;
        this.stats = stats;
        this.topic = topic;
        this.bootstrap = bootstrap;
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
        // historique complet : on reprend depuis le début du topic la première fois
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");
        props.put("fetch.min.bytes", "1");
        props.put("fetch.max.wait.ms", "100");
        props.put("max.poll.records", "1000");
        props.put("fetch.max.bytes", String.valueOf(4 * 1024 * 1024));
        props.put("max.partition.fetch.bytes", String.valueOf(1024 * 1024));
        props.put("reconnect.backoff.ms", "1000");
        props.put("reconnect.backoff.max.ms", "10000");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());
    }

    @PostConstruct
    void start() {
        thread = new Thread(this::run, "kafka-to-db");
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
                if (records.isEmpty()) {
                    continue;
                }
                List<Row> rows = new ArrayList<>(records.count());
                for (ConsumerRecord<String, String> r : records) {
                    stats.read.increment();
                    Row row = parse(r.value());
                    if (row == null) {
                        stats.invalid.increment();
                    } else {
                        rows.add(row);
                    }
                }
                try {
                    int inserted = insert(rows);
                    stats.inserted.add(inserted);
                    stats.duplicates.add(rows.size() - inserted);
                } catch (SQLException e) {
                    stats.dbErrors.increment();
                    log.warn("Écriture en base impossible ({}{}) - le lot sera relu dans 5 s", e,
                            e.getCause() == null ? "" : " / cause : " + e.getCause());
                    rewind(records);
                    sleep(5000);
                    continue;
                }
                consumer.commitSync(); // offsets validés seulement après l'insertion
            } catch (WakeupException e) {
                // arrêt demandé
            } catch (Exception e) {
                log.warn("Erreur Kafka ({}) - nouvel essai dans 5 s", e.toString());
                sleep(5000);
            }
        }
        KafkaConsumer<String, String> c = consumer;
        if (c != null) {
            c.close(Duration.ofSeconds(5));
        }
    }

    /** Revient au premier offset du lot sur chaque partition : poll() le relira. */
    private void rewind(ConsumerRecords<String, String> records) {
        for (TopicPartition tp : records.partitions()) {
            consumer.seek(tp, records.records(tp).get(0).offset());
        }
    }

    /** Insère le lot dans une transaction ; renvoie le nombre de lignes réellement insérées. */
    int insert(List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        try (Connection c = writer.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(INSERT)) {
                for (Row r : rows) {
                    bind(ps, r);
                    ps.addBatch();
                }
                int[] counts = ps.executeBatch();
                c.commit();
                int inserted = 0;
                for (int n : counts) {
                    inserted += Math.max(n, 0);
                }
                return inserted;
            } catch (SQLException e) {
                c.rollback();
                if (!isDataError(e)) {
                    throw e;
                }
            }
            // Une ligne refusée par la base (valeur incorrecte) bloquerait tout le lot indéfiniment :
            // on insère alors ligne par ligne et on écarte les lignes fautives.
            return insertOneByOne(c, rows);
        }
    }

    private int insertOneByOne(Connection c, List<Row> rows) throws SQLException {
        c.setAutoCommit(true);
        int inserted = 0;
        try (PreparedStatement ps = c.prepareStatement(INSERT)) {
            for (Row r : rows) {
                try {
                    bind(ps, r);
                    inserted += ps.executeUpdate();
                } catch (SQLException e) {
                    if (!isDataError(e)) {
                        throw e;
                    }
                    stats.invalid.increment();
                    log.warn("Mesure {}/{} refusée par la base : {}", r.braceletId(), r.seq(), e.getMessage());
                }
            }
        }
        return inserted;
    }

    /** Classe SQLSTATE 22 (donnée invalide) ou 23 (contrainte) : réessayer ne servirait à rien. */
    private static boolean isDataError(SQLException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof SQLException s && s.getSQLState() != null
                    && (s.getSQLState().startsWith("22") || s.getSQLState().startsWith("23"))) {
                return true;
            }
            if (t instanceof java.sql.BatchUpdateException b && b.getNextException() != null && b.getNextException() != t) {
                if (isDataError(b.getNextException())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void bind(PreparedStatement ps, Row r) throws SQLException {
        ps.setObject(1, OffsetDateTime.ofInstant(r.time(), ZoneOffset.UTC));
        ps.setString(2, r.patientId());
        ps.setString(3, r.braceletId());
        ps.setString(4, r.type());
        ps.setLong(5, r.seq());
        setInt(ps, 6, r.heartRate());
        setInt(ps, 7, r.spo2());
        if (r.temperature() == null) {
            ps.setNull(8, Types.REAL);
        } else {
            ps.setFloat(8, r.temperature());
        }
        setInt(ps, 9, r.systolic());
        setInt(ps, 10, r.diastolic());
        ps.setBoolean(11, r.anomaly());
    }

    private static void setInt(PreparedStatement ps, int index, Integer value) throws SQLException {
        if (value == null) {
            ps.setNull(index, Types.INTEGER);
        } else {
            ps.setInt(index, value);
        }
    }

    /** Message Kafka (mesure enrichie par ingestion) → ligne ; null si inexploitable. */
    Row parse(String value) {
        try {
            JsonNode n = value == null ? null : mapper.readTree(value);
            if (n == null || !n.isObject()) {
                return null;
            }
            String patientId = n.path("patient_id").asText(null);
            String braceletId = n.path("bracelet_id").asText(null);
            String type = n.path("type").asText(null);
            JsonNode seq = n.get("seq");
            JsonNode ts = n.get("ts");
            if (patientId == null || braceletId == null || type == null || seq == null || !seq.canConvertToLong()
                    || ts == null || !ts.isTextual()) {
                return null;
            }
            JsonNode temperature = n.get("temperature");
            return new Row(Instant.parse(ts.asText()), patientId, braceletId, type, seq.asLong(),
                    intOrNull(n, "heart_rate"), intOrNull(n, "spo2"),
                    temperature != null && temperature.isNumber() ? temperature.floatValue() : null,
                    intOrNull(n, "systolic"), intOrNull(n, "diastolic"),
                    n.path("anomaly").asBoolean(false));
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v != null && v.isNumber() && v.canConvertToInt() ? v.asInt() : null;
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
