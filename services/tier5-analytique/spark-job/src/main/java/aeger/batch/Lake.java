package aeger.batch;

import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.lit;

import java.sql.Date;
import java.time.LocalDate;

import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.catalyst.analysis.NoSuchTableException;

/**
 * Data Lake: Iceberg tables in the catalog {@code lake} (JDBC catalog in PostgreSQL database kpi,
 * schema lake; data files in s3://aeger-lake/warehouse on SeaweedFS). Every table is partitioned by
 * {@code day}, so re-running a day replaces exactly that day's files (atomic overwrite by filter).
 */
public final class Lake {

    private Lake() {
    }

    public static final String CATALOG = "lake";
    public static final String RAW_VITALS = CATALOG + ".raw.vitals";
    public static final String CLEANED_VITALS = CATALOG + ".cleaned.vitals";

    static final String RAW_COLUMNS = "day DATE, kafka_partition INT, kafka_offset BIGINT, kafka_timestamp TIMESTAMP,"
            + " kafka_key STRING, bracelet_id STRING, patient_id STRING, type STRING, seq BIGINT, ts TIMESTAMP,"
            + " heart_rate DOUBLE, spo2 DOUBLE, temperature DOUBLE, systolic DOUBLE, diastolic DOUBLE,"
            + " anomaly BOOLEAN, source STRING, received_at TIMESTAMP, value STRING";

    static final String CLEANED_COLUMNS = "day DATE, patient_id STRING, bracelet_id STRING, type STRING, seq BIGINT,"
            + " ts TIMESTAMP, heart_rate INT, spo2 INT, temperature DOUBLE, systolic INT, diastolic INT,"
            + " anomaly BOOLEAN, source STRING, received_at TIMESTAMP, kafka_timestamp TIMESTAMP, hour INT,"
            + " severity STRING, is_alert BOOLEAN";

    private static final String TABLE_OPTIONS = " USING iceberg PARTITIONED BY (day) TBLPROPERTIES ("
            + "'format-version'='2',"
            + " 'write.parquet.compression-codec'='zstd',"
            + " 'write.metadata.delete-after-commit.enabled'='true',"
            + " 'write.metadata.previous-versions-max'='50')";

    /** Creates the namespaces and tables when missing (idempotent). */
    public static void ensureTables(SparkSession spark) {
        for (String ns : new String[] {"raw", "cleaned", "kpi"}) {
            spark.sql("CREATE NAMESPACE IF NOT EXISTS " + CATALOG + "." + ns);
        }
        spark.sql("CREATE TABLE IF NOT EXISTS " + RAW_VITALS + " (" + RAW_COLUMNS + ")" + TABLE_OPTIONS);
        spark.sql("CREATE TABLE IF NOT EXISTS " + CLEANED_VITALS + " (" + CLEANED_COLUMNS + ")" + TABLE_OPTIONS);
        for (KpiTable t : KpiTable.ALL) {
            spark.sql("CREATE TABLE IF NOT EXISTS " + t.lakeTable() + " (" + t.sparkColumnsDdl() + ")" + TABLE_OPTIONS);
        }
    }

    public static Column isDay(LocalDate day) {
        return col("day").equalTo(lit(Date.valueOf(day)));
    }

    /**
     * Atomically replaces the rows of {@code day} with {@code rows} (one Iceberg commit: delete the day's
     * partition, add the new files). An empty {@code rows} just empties the day.
     */
    public static void overwriteDay(Dataset<Row> rows, String table, LocalDate day) throws NoSuchTableException {
        Dataset<Row> aligned = rows.selectExpr(columnsOf(rows.sparkSession(), table));
        aligned.writeTo(table).overwrite(isDay(day));
    }

    /** Rows of one day of a lake table. */
    public static Dataset<Row> readDay(SparkSession spark, String table, LocalDate day) {
        return spark.table(table).where(isDay(day));
    }

    /** Column list of the target table, so that the written dataset has exactly its columns and order. */
    private static String[] columnsOf(SparkSession spark, String table) {
        return spark.table(table).schema().fieldNames();
    }
}
