package aeger.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;

/** Mesure validée (contrat § 3). */
public record Measure(String braceletId, String patientId, String type, long seq, Instant ts,
                      int heartRate, int spo2, double temperature, int systolic, int diastolic,
                      boolean anomaly) {

    /**
     * Message écrit dans Kafka : la mesure (champs du contrat, dans l'ordre du contrat,
     * ts normalisé en UTC) + "source" ("mqtt" | "https") + "received_at".
     */
    public String toKafkaJson(ObjectMapper mapper, String source, Instant receivedAt) {
        ObjectNode n = mapper.createObjectNode();
        n.put("bracelet_id", braceletId);
        n.put("patient_id", patientId);
        n.put("type", type);
        n.put("seq", seq);
        n.put("ts", ts.toString());
        n.put("heart_rate", heartRate);
        n.put("spo2", spo2);
        n.put("temperature", temperature);
        n.put("systolic", systolic);
        n.put("diastolic", diastolic);
        n.put("anomaly", anomaly);
        n.put("source", source);
        n.put("received_at", receivedAt.toString());
        return n.toString();
    }
}
