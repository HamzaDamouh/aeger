package aeger.vitals;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Requêtes de lecture (rôle vitals_reader) sur l'hypertable measures. */
@Repository
public class VitalsRepository {

    /** Nombre maximal de points renvoyés par l'historique. */
    static final int MAX_POINTS = 2000;
    /** Tailles d'intervalle « rondes » (secondes) parmi lesquelles on choisit. */
    private static final int[] NICE_BUCKETS = {1, 2, 5, 10, 15, 20, 30, 60, 120, 180, 300, 600, 900, 1200, 1800, 3600};

    /**
     * Dernière mesure de chaque patient (7 derniers jours), filtrée par sous-chaîne du patient_id.
     * <p>
     * Pour rester rapide sur une table de plusieurs millions de lignes, on n'utilise pas un
     * DISTINCT ON sur toute la table : une requête récursive (« loose index scan ») saute d'un
     * patient_id au suivant grâce à l'index (patient_id, time DESC), puis, pour chaque patient retenu,
     * une sous-requête LATERAL lit sa dernière mesure par ce même index. La récursion produit les
     * patient_id dans l'ordre croissant et s'arrête dès que LIMIT est atteint.
     */
    static final String PATIENTS_SQL = """
            WITH RECURSIVE ids AS (
                (SELECT patient_id FROM measures ORDER BY patient_id LIMIT 1)
              UNION ALL
                SELECT (SELECT m.patient_id FROM measures m
                         WHERE m.patient_id > ids.patient_id
                         ORDER BY m.patient_id LIMIT 1)
                FROM ids
                WHERE ids.patient_id IS NOT NULL
            )
            SELECT last.*
            FROM ids
            CROSS JOIN LATERAL (
                SELECT m.time, m.patient_id, m.bracelet_id, m.type, m.seq, m.heart_rate, m.spo2,
                       m.temperature, m.systolic, m.diastolic, m.anomaly
                FROM measures m
                WHERE m.patient_id = ids.patient_id
                  AND m.time > now() - interval '7 days'
                ORDER BY m.time DESC
                LIMIT 1
            ) last
            WHERE ids.patient_id IS NOT NULL
              AND ids.patient_id ILIKE ?
            LIMIT ?
            """;

    /** Moyennes par intervalle (time_bucket TimescaleDB) sur les dernières heures. */
    static final String HISTORY_SQL = """
            SELECT time_bucket(make_interval(secs => ?), time) AS bucket,
                   round(avg(heart_rate), 1)           AS heart_rate,
                   round(avg(spo2), 1)                 AS spo2,
                   round(avg(temperature)::numeric, 2) AS temperature,
                   round(avg(systolic), 1)             AS systolic,
                   round(avg(diastolic), 1)            AS diastolic
            FROM measures
            WHERE patient_id = ?
              AND time > now() - make_interval(hours => ?)
            GROUP BY bucket
            ORDER BY bucket
            """;

    private final JdbcTemplate jdbc;

    public VitalsRepository(@Qualifier("readerJdbc") JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** [{"patient_id","bracelet_id","type","last_ts","last":{mesure}}] triés par patient_id. */
    public List<Map<String, Object>> patients(String q, int limit) {
        String pattern = "%" + escapeLike(q) + "%";
        List<Map<String, Object>> list = jdbc.query(PATIENTS_SQL, (rs, i) -> patientRow(rs), pattern, limit);
        list.sort(Comparator.comparing(m -> (String) m.get("patient_id")));
        return list;
    }

    /** {"patient_id","hours","bucket_seconds","points":[{"ts","heart_rate","spo2","temperature","systolic","diastolic"}]} */
    public Map<String, Object> history(String patientId, int hours) {
        int bucket = bucketSeconds(hours);
        List<Map<String, Object>> points = jdbc.query(HISTORY_SQL, (rs, i) -> {
            Map<String, Object> p = new LinkedHashMap<>();
            p.put("ts", rs.getObject("bucket", OffsetDateTime.class).toInstant().toString());
            p.put("heart_rate", decimal(rs, "heart_rate"));
            p.put("spo2", decimal(rs, "spo2"));
            p.put("temperature", decimal(rs, "temperature"));
            p.put("systolic", decimal(rs, "systolic"));
            p.put("diastolic", decimal(rs, "diastolic"));
            return p;
        }, (double) bucket, patientId, hours);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("patient_id", patientId);
        result.put("hours", hours);
        result.put("bucket_seconds", bucket);
        result.put("points", points);
        return result;
    }

    /** Plus petit intervalle « rond » donnant au plus ~2 000 points (1 h → 2 s, 24 h → 60 s, 168 h → 600 s). */
    static int bucketSeconds(int hours) {
        double minimum = hours * 3600.0 / MAX_POINTS;
        for (int b : NICE_BUCKETS) {
            if (b >= minimum) {
                return b;
            }
        }
        return (int) Math.ceil(minimum);
    }

    /** Échappe les caractères spéciaux de LIKE pour chercher q tel quel. */
    static String escapeLike(String q) {
        return q.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Map<String, Object> patientRow(ResultSet rs) throws SQLException {
        String ts = rs.getObject("time", OffsetDateTime.class).toInstant().toString();
        Map<String, Object> last = new LinkedHashMap<>();
        last.put("bracelet_id", rs.getString("bracelet_id"));
        last.put("patient_id", rs.getString("patient_id"));
        last.put("type", rs.getString("type"));
        last.put("seq", rs.getLong("seq"));
        last.put("ts", ts);
        last.put("heart_rate", rs.getObject("heart_rate", Integer.class));
        last.put("spo2", rs.getObject("spo2", Integer.class));
        Float t = rs.getObject("temperature", Float.class);
        last.put("temperature", t == null ? null : Double.valueOf(t.toString())); // 36.8 et non 36.799999
        last.put("systolic", rs.getObject("systolic", Integer.class));
        last.put("diastolic", rs.getObject("diastolic", Integer.class));
        last.put("anomaly", rs.getBoolean("anomaly"));

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("patient_id", last.get("patient_id"));
        row.put("bracelet_id", last.get("bracelet_id"));
        row.put("type", last.get("type"));
        row.put("last_ts", ts);
        row.put("last", last);
        return row;
    }

    private static Double decimal(ResultSet rs, String column) throws SQLException {
        BigDecimal v = rs.getBigDecimal(column);
        return v == null ? null : v.doubleValue();
    }
}
