package aeger.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThresholdRulesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Mesure normale du contrat (§ 3) : aucune alerte. */
    private static ObjectNode normal() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("bracelet_id", "hr-00042");
        n.put("patient_id", "P00042");
        n.put("type", "high_risk");
        n.put("seq", 1532);
        n.put("ts", "2026-11-18T14:03:05.120Z");
        n.put("heart_rate", 72);
        n.put("spo2", 97);
        n.put("temperature", 36.8);
        n.put("systolic", 121);
        n.put("diastolic", 79);
        n.put("anomaly", false);
        return n;
    }

    private static ObjectNode with(String parameter, double value) {
        ObjectNode n = normal();
        if (parameter.equals("temperature")) {
            n.put(parameter, value);
        } else {
            n.put(parameter, (int) value);
        }
        return n;
    }

    @Test
    void mesureNormaleSansAlerte() {
        ThresholdRules.Evaluation e = ThresholdRules.evaluate(normal());
        assertFalse(e.isAlert());
        assertNull(e.severity());
        assertTrue(e.reasons().isEmpty());
    }

    /** parameter, value, sévérité attendue (NONE = pas d'alerte), règle attendue. */
    @ParameterizedTest(name = "{0}={1} -> {2} {3}")
    @CsvSource({
            // heart_rate : WARNING < 40 ou > 130 ; CRITICAL < 35 ou > 150
            "heart_rate, 40, NONE, ''",
            "heart_rate, 39, WARNING, < 40",
            "heart_rate, 35, WARNING, < 40",
            "heart_rate, 34, CRITICAL, < 35",
            "heart_rate, 130, NONE, ''",
            "heart_rate, 131, WARNING, > 130",
            "heart_rate, 150, WARNING, > 130",
            "heart_rate, 151, CRITICAL, > 150",
            // spo2 : WARNING < 90 ; CRITICAL < 85
            "spo2, 90, NONE, ''",
            "spo2, 89, WARNING, < 90",
            "spo2, 85, WARNING, < 90",
            "spo2, 84, CRITICAL, < 85",
            "spo2, 100, NONE, ''",
            // temperature : WARNING < 35.0 ou > 39.5 ; CRITICAL < 34.0 ou > 40.5
            "temperature, 35.0, NONE, ''",
            "temperature, 34.9, WARNING, < 35.0",
            "temperature, 34.0, WARNING, < 35.0",
            "temperature, 33.9, CRITICAL, < 34.0",
            "temperature, 39.5, NONE, ''",
            "temperature, 39.6, WARNING, > 39.5",
            "temperature, 40.5, WARNING, > 39.5",
            "temperature, 40.6, CRITICAL, > 40.5",
            // systolic : WARNING < 90 ou > 180 ; CRITICAL < 80 ou > 200
            "systolic, 90, NONE, ''",
            "systolic, 89, WARNING, < 90",
            "systolic, 80, WARNING, < 90",
            "systolic, 79, CRITICAL, < 80",
            "systolic, 180, NONE, ''",
            "systolic, 181, WARNING, > 180",
            "systolic, 200, WARNING, > 180",
            "systolic, 201, CRITICAL, > 200",
            // diastolic : aucune règle
            "diastolic, 30, NONE, ''",
            "diastolic, 160, NONE, ''",
    })
    void seuils(String parameter, double value, String expected, String rule) {
        ThresholdRules.Evaluation e = ThresholdRules.evaluate(with(parameter, value));
        if (expected.equals("NONE")) {
            assertFalse(e.isAlert(), "aucune alerte attendue");
            return;
        }
        assertTrue(e.isAlert());
        assertEquals(ThresholdRules.Severity.valueOf(expected), e.severity());
        assertEquals(1, e.reasons().size());
        ThresholdRules.Reason r = e.reasons().get(0);
        assertEquals(parameter, r.parameter());
        assertEquals(rule, r.rule());
        assertEquals(value, r.value().asDouble());
    }

    @Test
    void anomalieToujoursWarning() {
        ObjectNode n = normal();
        n.put("anomaly", true);
        ThresholdRules.Evaluation e = ThresholdRules.evaluate(n);
        assertEquals(ThresholdRules.Severity.WARNING, e.severity());
        assertEquals("anomaly", e.reasons().get(0).parameter());
        assertEquals("anomaly", e.reasons().get(0).rule());
        assertTrue(e.reasons().get(0).value().booleanValue());
    }

    @Test
    void severiteLaPlusHauteDesReglesVraies() {
        ObjectNode n = normal();
        n.put("heart_rate", 135);   // WARNING
        n.put("spo2", 84);          // CRITICAL
        n.put("anomaly", true);     // WARNING
        ThresholdRules.Evaluation e = ThresholdRules.evaluate(n);
        assertEquals(ThresholdRules.Severity.CRITICAL, e.severity());
        assertEquals(3, e.reasons().size());
    }

    @Test
    void champsManquantsIgnores() {
        ObjectNode n = MAPPER.createObjectNode();
        n.put("bracelet_id", "std-00001");
        n.put("spo2", 88);
        ThresholdRules.Evaluation e = ThresholdRules.evaluate(n);
        assertEquals(ThresholdRules.Severity.WARNING, e.severity());
        assertEquals(1, e.reasons().size());
        assertFalse(ThresholdRules.evaluate(MAPPER.createObjectNode()).isAlert());
    }

    @Test
    void messageAlerteConformeAuContrat() {
        ObjectNode m = normal();
        m.put("spo2", 84);
        ThresholdRules.Evaluation e = ThresholdRules.evaluate(m);
        JsonNode a = AlertFactory.build(MAPPER, m, e, Instant.parse("2026-11-18T14:03:05.900Z"));
        assertEquals("P00042", a.get("patient_id").asText());
        assertEquals("hr-00042", a.get("bracelet_id").asText());
        assertEquals("high_risk", a.get("type").asText());
        assertEquals("CRITICAL", a.get("severity").asText());
        assertEquals("2026-11-18T14:03:05.120Z", a.get("measure_ts").asText());
        assertEquals("2026-11-18T14:03:05.900Z", a.get("detected_at").asText());
        assertEquals("spo2", a.get("reasons").get(0).get("parameter").asText());
        assertEquals(84, a.get("reasons").get(0).get("value").asInt());
        assertEquals("< 85", a.get("reasons").get(0).get("rule").asText());
        assertEquals(m, a.get("measure"));
        // identifiant stable pour une même mesure (bracelet_id + seq)
        JsonNode b = AlertFactory.build(MAPPER, m, e, Instant.now());
        assertEquals(a.get("alert_id"), b.get("alert_id"));
        assertEquals("CRITICAL P00042 hr-00042 spo2=84 (< 85)", AlertFactory.summary(a));
    }
}
