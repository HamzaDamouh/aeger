package aeger.batch;

import java.sql.Types;
import java.util.List;

/**
 * One KPI table, present twice with the same columns: in the lake ({@code lake.kpi.<name>}, Iceberg)
 * and in PostgreSQL ({@code kpi.<name>}, CONTRAT.md section 9, created by the infrastructure).
 */
public record KpiTable(String name, List<Column> columns) {

    /** A column: name, Spark/Iceberg SQL type, java.sql.Types code for the PostgreSQL insert. */
    public record Column(String name, String sparkType, int sqlType) {
    }

    private static Column c(String name, String sparkType, int sqlType) {
        return new Column(name, sparkType, sqlType);
    }

    public static final KpiTable DAILY_SUMMARY = new KpiTable("daily_summary", List.of(
            c("day", "DATE", Types.DATE),
            c("nb_measures", "BIGINT", Types.BIGINT),
            c("nb_patients", "INT", Types.INTEGER),
            c("nb_alerts", "BIGINT", Types.BIGINT),
            c("nb_high_risk_measures", "BIGINT", Types.BIGINT),
            c("nb_standard_measures", "BIGINT", Types.BIGINT),
            c("nb_anomalies", "BIGINT", Types.BIGINT),
            c("avg_heart_rate", "DOUBLE", Types.DOUBLE),
            c("avg_spo2", "DOUBLE", Types.DOUBLE),
            c("avg_temperature", "DOUBLE", Types.DOUBLE),
            c("pct_alert", "DOUBLE", Types.DOUBLE),
            c("computed_at", "TIMESTAMP", Types.TIMESTAMP_WITH_TIMEZONE)));

    public static final KpiTable DAILY_BY_TYPE = new KpiTable("daily_by_type", List.of(
            c("day", "DATE", Types.DATE),
            c("type", "STRING", Types.VARCHAR),
            c("nb_measures", "BIGINT", Types.BIGINT),
            c("nb_patients", "INT", Types.INTEGER),
            c("nb_alerts", "BIGINT", Types.BIGINT),
            c("avg_heart_rate", "DOUBLE", Types.DOUBLE),
            c("avg_spo2", "DOUBLE", Types.DOUBLE),
            c("avg_temperature", "DOUBLE", Types.DOUBLE)));

    public static final KpiTable HOURLY = new KpiTable("hourly", List.of(
            c("day", "DATE", Types.DATE),
            c("hour", "INT", Types.INTEGER),
            c("nb_measures", "BIGINT", Types.BIGINT),
            c("nb_alerts", "BIGINT", Types.BIGINT),
            c("avg_heart_rate", "DOUBLE", Types.DOUBLE)));

    public static final KpiTable TOP_PATIENTS = new KpiTable("top_patients", List.of(
            c("day", "DATE", Types.DATE),
            c("patient_id", "STRING", Types.VARCHAR),
            c("type", "STRING", Types.VARCHAR),
            c("nb_alerts", "BIGINT", Types.BIGINT),
            c("min_spo2", "INT", Types.INTEGER),
            c("max_heart_rate", "INT", Types.INTEGER)));

    public static final List<KpiTable> ALL = List.of(DAILY_SUMMARY, DAILY_BY_TYPE, HOURLY, TOP_PATIENTS);

    public String lakeTable() {
        return Lake.CATALOG + ".kpi." + name;
    }

    public String postgresTable() {
        return "kpi." + name;
    }

    /** "day DATE, nb_measures BIGINT, ..." for CREATE TABLE. */
    public String sparkColumnsDdl() {
        StringBuilder sb = new StringBuilder();
        for (Column col : columns) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(col.name()).append(' ').append(col.sparkType());
        }
        return sb.toString();
    }

    public String[] columnNames() {
        return columns.stream().map(Column::name).toArray(String[]::new);
    }
}
