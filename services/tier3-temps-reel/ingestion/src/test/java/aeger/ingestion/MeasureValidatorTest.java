package aeger.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeasureValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-11-18T14:03:10Z");

    private static ObjectNode valid() {
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

    @Test
    void mesureDuContratAcceptee() throws Exception {
        Measure m = MeasureValidator.validate(valid(), NOW);
        assertEquals("P00042", m.patientId());
        assertEquals(1532, m.seq());
        assertEquals(36.8, m.temperature());
    }

    @Test
    void braceletStandardDecaleDe1000() throws Exception {
        ObjectNode n = valid();
        n.put("bracelet_id", "std-00042");
        n.put("type", "standard");
        n.put("patient_id", "P01042");
        assertEquals("P01042", MeasureValidator.validate(n, NOW).patientId());
    }

    @Test
    void incoherencesRefusees() {
        ObjectNode n = valid();
        n.put("patient_id", "P01042");
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(n, NOW));
        ObjectNode t = valid();
        t.put("type", "standard");
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(t, NOW));
    }

    @Test
    void champManquantOuHorsPlage() {
        ObjectNode n = valid();
        n.remove("diastolic");
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(n, NOW));
        ObjectNode s = valid();
        s.put("spo2", 101);
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(s, NOW));
        ObjectNode h = valid();
        h.put("heart_rate", 72.5);
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(h, NOW));
    }

    @Test
    void fenetreDeTemps() {
        ObjectNode old = valid();
        old.put("ts", "2026-11-17T14:00:00Z");
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(old, NOW));
        ObjectNode future = valid();
        future.put("ts", "2026-11-18T14:09:00Z");
        assertThrows(MeasureValidator.InvalidMeasureException.class, () -> MeasureValidator.validate(future, NOW));
    }

    @Test
    void messageKafkaEnrichi() throws Exception {
        Measure m = MeasureValidator.validate(valid(), NOW);
        String json = m.toKafkaJson(MAPPER, "mqtt", NOW);
        var node = MAPPER.readTree(json);
        assertEquals("mqtt", node.get("source").asText());
        assertEquals("2026-11-18T14:03:10Z", node.get("received_at").asText());
        assertEquals("2026-11-18T14:03:05.120Z", node.get("ts").asText());
        assertEquals(36.8, node.get("temperature").asDouble());
    }
}
