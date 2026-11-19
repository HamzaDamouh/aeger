package aeger.alerting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Seuils d'alerte du contrat (§ 4). Une mesure déclenche une alerte si au moins une règle est vraie ;
 * la sévérité de l'alerte est la plus haute des règles vraies.
 *
 * <pre>
 * Paramètre     WARNING                CRITICAL
 * heart_rate    &lt; 40 ou &gt; 130         &lt; 35 ou &gt; 150
 * spo2          &lt; 90                   &lt; 85
 * temperature   &lt; 35.0 ou &gt; 39.5      &lt; 34.0 ou &gt; 40.5
 * systolic      &lt; 90 ou &gt; 180         &lt; 80 ou &gt; 200
 * anomaly=true  toujours (règle anomaly)
 * </pre>
 * Pour chaque paramètre on ne garde qu'une raison : la règle la plus sévère qui est vraie.
 */
public final class ThresholdRules {

    public enum Severity { WARNING, CRITICAL }

    /** Une raison d'alerte : {"parameter","value","rule","severity"}. */
    public record Reason(String parameter, JsonNode value, String rule, Severity severity) {
    }

    /** Résultat de l'évaluation (severity null = pas d'alerte). */
    public record Evaluation(Severity severity, List<Reason> reasons) {
        public boolean isAlert() {
            return severity != null;
        }
    }

    private enum Op { LT, GT }

    private record Rule(String parameter, Op op, double limit, String label, Severity severity) {
        boolean matches(double x) {
            return op == Op.LT ? x < limit : x > limit;
        }
    }

    /** Règles par paramètre, les CRITICAL d'abord (la première vraie l'emporte). */
    private static final List<Rule> RULES = List.of(
            new Rule("heart_rate", Op.LT, 35, "< 35", Severity.CRITICAL),
            new Rule("heart_rate", Op.GT, 150, "> 150", Severity.CRITICAL),
            new Rule("heart_rate", Op.LT, 40, "< 40", Severity.WARNING),
            new Rule("heart_rate", Op.GT, 130, "> 130", Severity.WARNING),

            new Rule("spo2", Op.LT, 85, "< 85", Severity.CRITICAL),
            new Rule("spo2", Op.LT, 90, "< 90", Severity.WARNING),

            new Rule("temperature", Op.LT, 34.0, "< 34.0", Severity.CRITICAL),
            new Rule("temperature", Op.GT, 40.5, "> 40.5", Severity.CRITICAL),
            new Rule("temperature", Op.LT, 35.0, "< 35.0", Severity.WARNING),
            new Rule("temperature", Op.GT, 39.5, "> 39.5", Severity.WARNING),

            new Rule("systolic", Op.LT, 80, "< 80", Severity.CRITICAL),
            new Rule("systolic", Op.GT, 200, "> 200", Severity.CRITICAL),
            new Rule("systolic", Op.LT, 90, "< 90", Severity.WARNING),
            new Rule("systolic", Op.GT, 180, "> 180", Severity.WARNING)
    );

    private static final List<String> PARAMETERS = List.of("heart_rate", "spo2", "temperature", "systolic");

    private ThresholdRules() {
    }

    public static Evaluation evaluate(JsonNode measure) {
        List<Reason> reasons = new ArrayList<>();
        for (String parameter : PARAMETERS) {
            JsonNode value = measure.get(parameter);
            if (value == null || !value.isNumber()) {
                continue; // champ absent : règle non applicable
            }
            double x = value.asDouble();
            for (Rule rule : RULES) {
                if (rule.parameter().equals(parameter) && rule.matches(x)) {
                    reasons.add(new Reason(parameter, value, rule.label(), rule.severity()));
                    break;
                }
            }
        }
        JsonNode anomaly = measure.get("anomaly");
        if (anomaly != null && anomaly.isBoolean() && anomaly.booleanValue()) {
            reasons.add(new Reason("anomaly", BooleanNode.TRUE, "anomaly", Severity.WARNING));
        }

        Severity severity = null;
        for (Reason r : reasons) {
            if (severity == null || r.severity().compareTo(severity) > 0) {
                severity = r.severity();
            }
        }
        return new Evaluation(severity, List.copyOf(reasons));
    }
}
