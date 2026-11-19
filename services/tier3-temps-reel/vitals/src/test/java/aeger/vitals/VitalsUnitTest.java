package aeger.vitals;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VitalsUnitTest {

    @Test
    void intervalleDeMoyenneJamaisPlusDe2000Points() {
        assertEquals(2, VitalsRepository.bucketSeconds(1));
        assertEquals(60, VitalsRepository.bucketSeconds(24));
        assertEquals(600, VitalsRepository.bucketSeconds(168));
        for (int h = 1; h <= 168; h++) {
            int b = VitalsRepository.bucketSeconds(h);
            assertTrue(h * 3600 / b <= VitalsRepository.MAX_POINTS, "hours=" + h + " bucket=" + b);
        }
    }

    @Test
    void echappementLike() {
        assertEquals("P0004", VitalsRepository.escapeLike("P0004"));
        assertEquals("a\\%b\\_c\\\\", VitalsRepository.escapeLike("a%b_c\\"));
    }

    @Test
    void fichierEnvDeVault() {
        Map<String, Object> v = SecretsEnvFile.parse(List.of(
                "# commentaire", "", "DB_WRITER_USER=vitals_writer", "export DB_WRITER_PASSWORD=\"a=b c\"",
                "DB_READER_USER='vitals_reader'", "ligne invalide"));
        assertEquals("vitals_writer", v.get("DB_WRITER_USER"));
        assertEquals("a=b c", v.get("DB_WRITER_PASSWORD"));
        assertEquals("vitals_reader", v.get("DB_READER_USER"));
        assertEquals(3, v.size());
    }

    @Test
    void lectureDuMessageKafka() {
        MeasureWriter w = new MeasureWriter(null, new ObjectMapper(), new Stats(), "k:9093", "vitals.raw", "vitals",
                "/opt/aeger/secrets", "vitals");
        MeasureWriter.Row r = w.parse("""
                {"bracelet_id":"hr-00042","patient_id":"P00042","type":"high_risk","seq":1532,
                 "ts":"2026-11-18T14:03:05.120Z","heart_rate":72,"spo2":97,"temperature":36.8,
                 "systolic":121,"diastolic":79,"anomaly":false,"source":"mqtt",
                 "received_at":"2026-11-18T14:03:05.200Z"}""");
        assertEquals(Instant.parse("2026-11-18T14:03:05.120Z"), r.time());
        assertEquals("P00042", r.patientId());
        assertEquals(1532L, r.seq());
        assertEquals(36.8f, r.temperature());
        assertEquals(79, r.diastolic());
        assertNull(w.parse("pas du json"));
        assertNull(w.parse("{\"patient_id\":\"P00001\"}"));
    }
}
