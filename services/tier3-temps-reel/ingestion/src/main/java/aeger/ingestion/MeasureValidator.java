package aeger.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Validation d'une mesure (contrat § 3) :
 * tous les champs présents et bien typés, plages physiologiques, fenêtre de temps
 * (24 h dans le passé, 5 min dans le futur) et cohérence bracelet_id / type / patient_id.
 */
public final class MeasureValidator {

    /** hr-00001..hr-01000 → P00001..P01000 ; std-00001.. → P01001.. (numéro + 1000). */
    private static final Pattern BRACELET = Pattern.compile("^(hr|std)-(\\d{5})$");
    static final Duration MAX_PAST = Duration.ofHours(24);
    static final Duration MAX_FUTURE = Duration.ofMinutes(5);

    private MeasureValidator() {
    }

    /** Exception portant la raison du rejet (affichée dans la ligne de statistiques). */
    public static class InvalidMeasureException extends Exception {
        public InvalidMeasureException(String message) {
            super(message, null, false, false); // pas de pile : c'est un cas normal
        }
    }

    public static Measure validate(JsonNode n, Instant now) throws InvalidMeasureException {
        if (n == null || !n.isObject()) {
            throw new InvalidMeasureException("la mesure n'est pas un objet JSON");
        }
        String braceletId = text(n, "bracelet_id");
        String patientId = text(n, "patient_id");
        String type = text(n, "type");
        long seq = seq(n);
        Instant ts = timestamp(n);
        int heartRate = (int) integer(n, "heart_rate", 20, 250);
        int spo2 = (int) integer(n, "spo2", 50, 100);
        double temperature = number(n, "temperature", 30, 43);
        int systolic = (int) integer(n, "systolic", 50, 260);
        int diastolic = (int) integer(n, "diastolic", 30, 160);
        JsonNode anomaly = n.get("anomaly");
        if (anomaly == null || !anomaly.isBoolean()) {
            throw new InvalidMeasureException("anomaly absent ou non booléen");
        }
        if (ts.isBefore(now.minus(MAX_PAST)) || ts.isAfter(now.plus(MAX_FUTURE))) {
            throw new InvalidMeasureException("ts hors fenêtre (-24 h / +5 min) : " + ts);
        }
        checkIdentity(braceletId, type, patientId);
        return new Measure(braceletId, patientId, type, seq, ts, heartRate, spo2, temperature,
                systolic, diastolic, anomaly.booleanValue());
    }

    /** bracelet_id cohérent avec type et patient_id. */
    static void checkIdentity(String braceletId, String type, String patientId) throws InvalidMeasureException {
        Matcher m = BRACELET.matcher(braceletId);
        if (!m.matches()) {
            throw new InvalidMeasureException("bracelet_id invalide : " + braceletId);
        }
        boolean highRisk = m.group(1).equals("hr");
        int number = Integer.parseInt(m.group(2));
        // hr limité à 1000 pour ne pas chevaucher les patients des bracelets standards
        if (number < 1 || number > (highRisk ? 1000 : 98_999)) {
            throw new InvalidMeasureException("numéro de bracelet hors plage : " + braceletId);
        }
        String expectedType = highRisk ? "high_risk" : "standard";
        if (!expectedType.equals(type)) {
            throw new InvalidMeasureException("type " + type + " incohérent avec " + braceletId);
        }
        String expectedPatient = String.format("P%05d", highRisk ? number : number + 1000);
        if (!expectedPatient.equals(patientId)) {
            throw new InvalidMeasureException("patient_id " + patientId + " incohérent avec " + braceletId);
        }
    }

    private static String text(JsonNode n, String field) throws InvalidMeasureException {
        JsonNode v = n.get(field);
        if (v == null || !v.isTextual() || v.asText().isEmpty()) {
            throw new InvalidMeasureException(field + " absent ou non textuel");
        }
        return v.asText();
    }

    private static long seq(JsonNode n) throws InvalidMeasureException {
        JsonNode v = n.get("seq");
        if (v == null || !v.isIntegralNumber() || !v.canConvertToLong() || v.asLong() < 0) {
            throw new InvalidMeasureException("seq absent ou invalide");
        }
        return v.asLong();
    }

    private static Instant timestamp(JsonNode n) throws InvalidMeasureException {
        JsonNode v = n.get("ts");
        if (v == null || !v.isTextual()) {
            throw new InvalidMeasureException("ts absent");
        }
        try {
            return Instant.parse(v.asText()); // ISO-8601, avec Z ou un décalage
        } catch (DateTimeParseException e) {
            throw new InvalidMeasureException("ts illisible : " + v.asText());
        }
    }

    private static double number(JsonNode n, String field, double min, double max) throws InvalidMeasureException {
        JsonNode v = n.get(field);
        if (v == null || !v.isNumber()) {
            throw new InvalidMeasureException(field + " absent ou non numérique");
        }
        double d = v.asDouble();
        if (d < min || d > max) {
            throw new InvalidMeasureException(field + " hors plage [" + min + ", " + max + "] : " + d);
        }
        return d;
    }

    private static long integer(JsonNode n, String field, long min, long max) throws InvalidMeasureException {
        double d = number(n, field, min, max);
        if (d != Math.rint(d)) {
            throw new InvalidMeasureException(field + " doit être entier : " + d);
        }
        return (long) d;
    }
}
