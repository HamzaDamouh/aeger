package aeger.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.Future;

/**
 * Chaîne de traitement commune à MQTT et HTTPS :
 * validation → dédoublonnage → enrichissement (source, received_at) → Kafka.
 */
@Service
public class IngestionService {

    public enum Outcome { ACCEPTED, DUPLICATE, REJECTED, FAILED }

    /** Résultat pour une mesure ; delivery = accusé Kafka à attendre si ACCEPTED. */
    public record Result(Outcome outcome, Future<RecordMetadata> delivery) {
    }

    private final Deduplicator dedup = new Deduplicator();
    private final KafkaPublisher publisher;
    private final ObjectMapper mapper;
    private final Stats stats;

    public IngestionService(KafkaPublisher publisher, ObjectMapper mapper, Stats stats) {
        this.publisher = publisher;
        this.mapper = mapper;
        this.stats = stats;
    }

    public Result submit(JsonNode node, String source, Instant receivedAt) {
        Measure m;
        try {
            m = MeasureValidator.validate(node, receivedAt);
        } catch (MeasureValidator.InvalidMeasureException e) {
            stats.rejected(e.getMessage());
            return new Result(Outcome.REJECTED, null);
        }
        if (!dedup.markIfNew(m.braceletId(), m.seq())) {
            stats.duplicate();
            return new Result(Outcome.DUPLICATE, null);
        }
        String json = m.toKafkaJson(mapper, source, receivedAt);
        try {
            Future<RecordMetadata> f = publisher.send(m.patientId(), json, (metadata, error) -> {
                if (error == null) {
                    stats.published();
                } else {
                    // non écrite : on l'oublie pour qu'un renvoi du bracelet soit accepté
                    dedup.forget(m.braceletId(), m.seq());
                    stats.kafkaFailure(error);
                }
            });
            return new Result(Outcome.ACCEPTED, f);
        } catch (RuntimeException e) {
            dedup.forget(m.braceletId(), m.seq());
            stats.kafkaFailure(e);
            return new Result(Outcome.FAILED, null);
        }
    }
}
