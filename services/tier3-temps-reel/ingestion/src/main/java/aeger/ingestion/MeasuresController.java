package aeger.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * POST /measures (appelé par NGINX, CN nginx) : lot de mesures d'un bracelet standard.
 * L'en-tête X-Client-CN porte le CN du certificat du bracelet.
 * <p>
 * Réponses : 202 {"accepted","duplicates","rejected"} ; 400 JSON illisible ;
 * 403 bracelet_id ≠ X-Client-CN ; 503 Kafka indisponible (le bracelet doit renvoyer son lot,
 * les mesures déjà écrites seront alors comptées comme doublons).
 */
@RestController
public class MeasuresController {

    private static final Logger log = LoggerFactory.getLogger(MeasuresController.class);
    /** Attente maximale de l'accusé Kafka avant de répondre. */
    private static final long KAFKA_WAIT_NANOS = TimeUnit.SECONDS.toNanos(20);

    private final IngestionService service;
    private final KafkaPublisher publisher;
    private final ObjectMapper mapper;
    private final Stats stats;

    public MeasuresController(IngestionService service, KafkaPublisher publisher, ObjectMapper mapper, Stats stats) {
        this.service = service;
        this.publisher = publisher;
        this.mapper = mapper;
        this.stats = stats;
    }

    @PostMapping("/measures")
    public ResponseEntity<Map<String, Object>> post(
            @RequestHeader(value = "X-Client-CN", required = false) String clientCn,
            @RequestBody(required = false) byte[] body) {
        Instant receivedAt = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        if (clientCn == null || clientCn.isBlank()) {
            return error(403, "en-tête X-Client-CN manquant");
        }

        JsonNode root = null;
        try {
            root = body == null || body.length == 0 ? null : mapper.readTree(body);
        } catch (IOException e) {
            // JSON illisible : traité ci-dessous
        }
        if (root == null || !(root.isArray() || root.isObject())) {
            return error(400, "JSON illisible : tableau de mesures attendu");
        }
        List<JsonNode> items = new ArrayList<>();
        if (root.isArray()) {
            root.forEach(items::add);
        } else {
            items.add(root); // tolérance : une mesure seule hors tableau
        }

        // Un bracelet ne peut envoyer que ses propres mesures
        for (JsonNode item : items) {
            JsonNode b = item.get("bracelet_id");
            if (b != null && b.isTextual() && !b.asText().equals(clientCn)) {
                log.warn("Lot refusé : bracelet_id {} différent du certificat {}", b.asText(), clientCn);
                return error(403, "bracelet_id différent du certificat client");
            }
        }

        if (!publisher.isReady()) {
            return ResponseEntity.status(503).header("Retry-After", "30")
                    .body(Map.of("error", "Kafka indisponible, renvoyer le lot plus tard"));
        }

        int accepted = 0, duplicates = 0, rejected = 0;
        boolean failed = false;
        List<Future<RecordMetadata>> pending = new ArrayList<>();
        for (JsonNode item : items) {
            stats.httpMeasure();
            IngestionService.Result r = service.submit(item, "https", receivedAt);
            switch (r.outcome()) {
                case ACCEPTED -> {
                    accepted++;
                    pending.add(r.delivery());
                }
                case DUPLICATE -> duplicates++;
                case REJECTED -> rejected++;
                case FAILED -> failed = true;
            }
        }

        // Attendre les accusés Kafka : en cas d'échec le bracelet pourra renvoyer son lot.
        long deadline = System.nanoTime() + KAFKA_WAIT_NANOS;
        for (Future<RecordMetadata> f : pending) {
            if (failed) {
                break;
            }
            try {
                f.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException e) {
                failed = true;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failed = true;
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("accepted", accepted);
        result.put("duplicates", duplicates);
        result.put("rejected", rejected);
        if (failed) {
            result.put("error", "écriture Kafka non confirmée, renvoyer le lot");
            return ResponseEntity.status(503).header("Retry-After", "30").body(result);
        }
        return ResponseEntity.accepted().body(result);
    }

    private static ResponseEntity<Map<String, Object>> error(int status, String message) {
        return ResponseEntity.status(status).body(Map.of("error", message));
    }
}
