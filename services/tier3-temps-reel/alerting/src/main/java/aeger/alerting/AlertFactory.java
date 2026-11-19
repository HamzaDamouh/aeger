package aeger.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.StringJoiner;
import java.util.UUID;

/** Construit le message « alerte » du contrat (§ 4). */
public final class AlertFactory {

    private AlertFactory() {
    }

    /**
     * alert_id est déterministe (UUID dérivé de bracelet_id + seq) : si une mesure est relue
     * (redémarrage, rééquilibrage Kafka), l'alerte a le même identifiant et notification l'ignore.
     */
    public static ObjectNode build(ObjectMapper mapper, JsonNode measure, ThresholdRules.Evaluation eval, Instant detectedAt) {
        String braceletId = measure.path("bracelet_id").asText();
        ObjectNode a = mapper.createObjectNode();
        a.put("alert_id", UUID.nameUUIDFromBytes((braceletId + ":" + measure.path("seq").asText())
                .getBytes(StandardCharsets.UTF_8)).toString());
        a.put("patient_id", measure.path("patient_id").asText());
        a.put("bracelet_id", braceletId);
        a.put("type", measure.path("type").asText());
        a.put("severity", eval.severity().name());
        a.put("measure_ts", measure.path("ts").asText());
        a.put("detected_at", detectedAt.truncatedTo(ChronoUnit.MILLIS).toString());
        ArrayNode reasons = a.putArray("reasons");
        for (ThresholdRules.Reason r : eval.reasons()) {
            ObjectNode o = reasons.addObject();
            o.put("parameter", r.parameter());
            o.set("value", r.value());
            o.put("rule", r.rule());
            o.put("severity", r.severity().name());
        }
        a.set("measure", measure);
        return a;
    }

    /** Résumé d'une ligne pour les journaux : "CRITICAL P00042 hr-00042 spo2=84 (< 85)". */
    public static String summary(JsonNode alert) {
        StringJoiner reasons = new StringJoiner(", ");
        for (JsonNode r : alert.path("reasons")) {
            reasons.add(r.path("parameter").asText() + "=" + r.path("value").asText() + " (" + r.path("rule").asText() + ")");
        }
        return alert.path("severity").asText() + " " + alert.path("patient_id").asText() + " "
                + alert.path("bracelet_id").asText() + " " + reasons;
    }
}
