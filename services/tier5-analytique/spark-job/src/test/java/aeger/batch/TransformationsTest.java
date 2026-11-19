package aeger.batch;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.RowFactory;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.types.DataTypes;
import org.apache.spark.sql.types.StructType;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TransformationsTest {

    private static final LocalDate DAY = LocalDate.of(2026, 11, 18);
    private static final StructType KAFKA_LIKE = new StructType()
            .add("kafka_partition", DataTypes.IntegerType)
            .add("kafka_offset", DataTypes.LongType)
            .add("kafka_timestamp", DataTypes.TimestampType)
            .add("kafka_key", DataTypes.StringType)
            .add("value", DataTypes.StringType);

    private static SparkSession spark;

    @BeforeAll
    static void startSpark() {
        spark = SparkSession.builder()
                .master("local[2]")
                .appName("aeger-batch-test")
                .config("spark.sql.session.timeZone", "Europe/Paris")
                .config("spark.sql.shuffle.partitions", "2")
                .config("spark.ui.enabled", "false")
                .config("spark.driver.host", "127.0.0.1")
                .config("spark.driver.bindAddress", "127.0.0.1")
                .getOrCreate();
    }

    @AfterAll
    static void stopSpark() {
        if (spark != null) {
            spark.stop();
        }
    }

    // ------------------------------------------------------------------------------- test data

    private static String measure(String bracelet, String patient, String type, long seq, String ts, double hr,
                                  double spo2, double temp, double sys, double dia, boolean anomaly, String recv) {
        return String.format(Locale.ROOT,
                "{\"bracelet_id\":\"%s\",\"patient_id\":\"%s\",\"type\":\"%s\",\"seq\":%d,\"ts\":\"%s\","
                        + "\"heart_rate\":%s,\"spo2\":%s,\"temperature\":%s,\"systolic\":%s,\"diastolic\":%s,"
                        + "\"anomaly\":%s,\"source\":\"%s\",\"received_at\":\"%s\"}",
                bracelet, patient, type, seq, ts, num(hr), num(spo2), num(temp), num(sys), num(dia), anomaly,
                bracelet.startsWith("hr-") ? "mqtt" : "https", recv);
    }

    private static String num(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }

    private static String normal(String bracelet, String patient, String type, long seq, String ts, String recv) {
        return measure(bracelet, patient, type, seq, ts, 72, 97, 36.8, 121, 79, false, recv);
    }

    private static Row rec(int partition, long offset, String kafkaTs, String key, String value) {
        return RowFactory.create(partition, offset, Timestamp.from(Instant.parse(kafkaTs)), key, value);
    }

    private static String minus(String isoInstant, Duration d) {
        return Instant.parse(isoInstant).minus(d).toString();
    }

    /** 19 Kafka records: 11 valid distinct measures, 1 duplicate, 7 invalid. 6 alerts. */
    private static Dataset<Row> sampleRecords() {
        String r = "2026-11-18T13:05:00Z";                       // reception (14:05 Paris)
        String t = "2026-11-18T13:04:00.120Z";                   // measure time
        List<Row> rows = new ArrayList<>();
        // 1 normal high risk
        rows.add(rec(0, 10, r, "P00042", normal("hr-00042", "P00042", "high_risk", 1, t, r)));
        // 2 duplicate of 1 (same bracelet + seq), received later on another partition -> removed
        rows.add(rec(1, 3, "2026-11-18T13:05:01Z", "P00042",
                measure("hr-00042", "P00042", "high_risk", 1, t, 99, 97, 36.8, 121, 79, false, r)));
        // 3 spo2 84 -> CRITICAL
        rows.add(rec(0, 11, r, "P00042", measure("hr-00042", "P00042", "high_risk", 2, t, 70, 84, 36.8, 121, 79, false, r)));
        // 4 standard, heart rate 135 -> WARNING, hourly batch received at 10:00Z (11h Paris)
        rows.add(rec(2, 5, "2026-11-18T10:00:00Z", "P01042",
                measure("std-00042", "P01042", "standard", 7, "2026-11-18T09:10:00Z", 135, 96, 36.9, 120, 80, false,
                        "2026-11-18T10:00:00Z")));
        // 5 standard anomaly flag (decimal heart rate is rounded) -> WARNING
        rows.add(rec(2, 6, r, "P01001", measure("std-00001", "P01001", "standard", 1, t, 72.4, 97, 36.8, 121, 79, true, r)));
        // 6 spo2 out of range -> invalid
        rows.add(rec(0, 12, r, "P00043", measure("hr-00043", "P00043", "high_risk", 1, t, 72, 120, 36.8, 121, 79, false, r)));
        // 7 patient_id inconsistent with bracelet -> invalid
        rows.add(rec(0, 13, r, "P00045", normal("hr-00044", "P00045", "high_risk", 1, t, r)));
        // 8 type inconsistent with bracelet -> invalid
        rows.add(rec(0, 14, r, "P01005", normal("std-00005", "P01005", "high_risk", 1, t, r)));
        // 9 missing field (diastolic) -> invalid
        rows.add(rec(0, 15, r, "P00046",
                "{\"bracelet_id\":\"hr-00046\",\"patient_id\":\"P00046\",\"type\":\"high_risk\",\"seq\":1,"
                        + "\"ts\":\"" + t + "\",\"heart_rate\":72,\"spo2\":97,\"temperature\":36.8,\"systolic\":121,"
                        + "\"anomaly\":false,\"source\":\"mqtt\",\"received_at\":\"" + r + "\"}"));
        // 10 unreadable JSON -> kept in raw, invalid
        rows.add(rec(1, 4, r, null, "{not json"));
        // 11 ts more than 24 h before reception -> invalid
        rows.add(rec(0, 16, r, "P00047", normal("hr-00047", "P00047", "high_risk", 1, minus(r, Duration.ofHours(25)), r)));
        // 12 ts more than 5 min after reception -> invalid
        rows.add(rec(0, 17, r, "P00048", normal("hr-00048", "P00048", "high_risk", 1,
                Instant.parse(r).plus(Duration.ofMinutes(10)).toString(), r)));
        // 13 temperature 40.6 -> CRITICAL
        rows.add(rec(1, 5, r, "P00050", measure("hr-00050", "P00050", "high_risk", 1, t, 80, 95, 40.6, 120, 80, false, r)));
        // 14 systolic 185 -> WARNING
        rows.add(rec(1, 6, r, "P00051", measure("hr-00051", "P00051", "high_risk", 1, t, 80, 95, 37, 185, 80, false, r)));
        // 15 heart rate exactly 130 -> no alert (rule is > 130)
        rows.add(rec(1, 7, r, "P00052", measure("hr-00052", "P00052", "high_risk", 1, t, 130, 95, 37, 120, 80, false, r)));
        // 16 temperature 39.5 and spo2 90 -> no alert (boundaries)
        rows.add(rec(1, 8, r, "P00053", measure("hr-00053", "P00053", "high_risk", 1, t, 80, 90, 39.5, 120, 80, false, r)));
        // 17 ts with an explicit offset, received at 15:00:30 Paris
        rows.add(rec(1, 9, "2026-11-18T14:00:30Z", "P00054",
                normal("hr-00054", "P00054", "high_risk", 1, "2026-11-18T15:00:00.000+01:00", "2026-11-18T14:00:30Z")));
        // 18 std-09000 -> P10000, received at 00:30 Paris
        rows.add(rec(2, 7, "2026-11-17T23:30:00Z", "P10000",
                normal("std-09000", "P10000", "standard", 3, "2026-11-17T23:20:00Z", "2026-11-17T23:30:00Z")));
        // 19 heart rate 155 -> CRITICAL (second alert of P00042)
        rows.add(rec(0, 18, r, "P00042", measure("hr-00042", "P00042", "high_risk", 3, t, 155, 95, 37, 120, 80, false, r)));
        return spark.createDataFrame(rows, KAFKA_LIKE);
    }

    private static Map<String, Row> byKey(List<Row> rows, String... keyColumns) {
        Map<String, Row> m = new HashMap<>();
        for (Row row : rows) {
            StringBuilder k = new StringBuilder();
            for (String c : keyColumns) {
                k.append(String.valueOf((Object) row.getAs(c))).append('|');
            }
            m.put(k.toString(), row);
        }
        return m;
    }

    // ------------------------------------------------------------------------------------ tests

    @Test
    void parseKeepsEveryRecordIncludingUnreadableOnes() {
        Dataset<Row> raw = Transformations.parseRaw(sampleRecords(), DAY);
        assertEquals(19, raw.count());
        assertEquals(1, raw.where("bracelet_id IS NULL").count(), "unreadable JSON kept with null columns");
        Row first = raw.where("kafka_partition = 0 AND kafka_offset = 10").first();
        assertEquals("hr-00042", first.getAs("bracelet_id"));
        assertEquals(Timestamp.from(Instant.parse("2026-11-18T13:04:00.120Z")), first.getAs("ts"));
        assertEquals(Timestamp.from(Instant.parse("2026-11-18T13:05:00Z")), first.getAs("received_at"));
        assertEquals("mqtt", first.getAs("source"));
        assertEquals(java.sql.Date.valueOf(DAY), first.getAs("day"));
        assertEquals(List.of(Lake.RAW_COLUMNS.split(",\\s*")).stream().map(s -> s.split(" ")[0]).collect(Collectors.toList()),
                List.of(raw.columns()), "parseRaw columns = lake.raw.vitals columns");
    }

    @Test
    void cleanValidatesDeduplicatesAndFlagsAlerts() {
        Dataset<Row> raw = Transformations.parseRaw(sampleRecords(), DAY);
        assertEquals(12, Transformations.validate(raw).count(), "7 invalid records removed");
        Dataset<Row> cleaned = Transformations.clean(raw);
        assertEquals(List.of(Lake.CLEANED_COLUMNS.split(",\\s*")).stream().map(s -> s.split(" ")[0]).collect(Collectors.toList()),
                List.of(cleaned.columns()), "clean columns = lake.cleaned.vitals columns");

        Map<String, Row> m = byKey(cleaned.collectAsList(), "bracelet_id", "seq");
        assertEquals(11, m.size(), "11 distinct valid measures");

        Row first = m.get("hr-00042|1|");
        assertEquals(72, (int) first.getAs("heart_rate"), "the first received copy is kept");
        assertNull(first.getAs("severity"));
        assertFalse((Boolean) first.getAs("is_alert"));
        assertEquals(14, (int) first.getAs("hour"), "13:05Z = 14h Paris");

        assertEquals("CRITICAL", m.get("hr-00042|2|").getAs("severity"));
        assertEquals("CRITICAL", m.get("hr-00042|3|").getAs("severity"));
        assertEquals("WARNING", m.get("std-00042|7|").getAs("severity"));
        assertEquals(11, (int) m.get("std-00042|7|").getAs("hour"));
        assertEquals("WARNING", m.get("std-00001|1|").getAs("severity"));
        assertEquals(72, (int) m.get("std-00001|1|").getAs("heart_rate"));
        assertEquals("CRITICAL", m.get("hr-00050|1|").getAs("severity"));
        assertEquals("WARNING", m.get("hr-00051|1|").getAs("severity"));
        assertFalse((Boolean) m.get("hr-00052|1|").getAs("is_alert"));
        assertFalse((Boolean) m.get("hr-00053|1|").getAs("is_alert"));
        assertTrue(m.containsKey("hr-00054|1|"), "ts with +01:00 offset accepted");
        assertEquals("P10000", m.get("std-09000|3|").getAs("patient_id"));
        assertEquals(0, (int) m.get("std-09000|3|").getAs("hour"));
        for (String invalid : new String[] {"hr-00043|1|", "hr-00044|1|", "std-00005|1|", "hr-00046|1|",
                "hr-00047|1|", "hr-00048|1|"}) {
            assertFalse(m.containsKey(invalid), invalid + " should be rejected");
        }
        assertEquals(6, cleaned.where("is_alert").count());
    }

    @Test
    void kpisOfASampleDay() {
        Dataset<Row> cleaned = Transformations.clean(Transformations.parseRaw(sampleRecords(), DAY)).cache();
        Timestamp now = Timestamp.from(Instant.parse("2026-11-19T01:00:00Z"));
        Transformations.Kpis k = Transformations.kpis(cleaned, DAY, now);

        List<Row> summary = k.dailySummary().collectAsList();
        assertEquals(1, summary.size());
        Row s = summary.get(0);
        assertEquals(List.of(KpiTable.DAILY_SUMMARY.columnNames()), List.of(k.dailySummary().columns()));
        assertEquals(11L, (long) s.getAs("nb_measures"));
        assertEquals(9, (int) s.getAs("nb_patients"));
        assertEquals(6L, (long) s.getAs("nb_alerts"));
        assertEquals(8L, (long) s.getAs("nb_high_risk_measures"));
        assertEquals(3L, (long) s.getAs("nb_standard_measures"));
        assertEquals(1L, (long) s.getAs("nb_anomalies"));
        assertEquals(6 * 100.0 / 11, (double) s.getAs("pct_alert"), 1e-9);
        assertEquals(now, s.getAs("computed_at"));

        assertEquals(List.of(KpiTable.DAILY_BY_TYPE.columnNames()), List.of(k.dailyByType().columns()));
        Map<String, Row> byType = byKey(k.dailyByType().collectAsList(), "type");
        assertEquals(2, byType.size());
        assertEquals(8L, (long) byType.get("high_risk|").getAs("nb_measures"));
        assertEquals(6, (int) byType.get("high_risk|").getAs("nb_patients"));
        assertEquals(4L, (long) byType.get("high_risk|").getAs("nb_alerts"));
        assertEquals(3L, (long) byType.get("standard|").getAs("nb_measures"));
        assertEquals(2L, (long) byType.get("standard|").getAs("nb_alerts"));

        assertEquals(List.of(KpiTable.HOURLY.columnNames()), List.of(k.hourly().columns()));
        Map<String, Row> hourly = byKey(k.hourly().collectAsList(), "hour");
        assertEquals(24, hourly.size());
        assertEquals(1L, (long) hourly.get("0|").getAs("nb_measures"));
        assertEquals(1L, (long) hourly.get("11|").getAs("nb_measures"));
        assertEquals(1L, (long) hourly.get("11|").getAs("nb_alerts"));
        assertEquals(8L, (long) hourly.get("14|").getAs("nb_measures"));
        assertEquals(1L, (long) hourly.get("15|").getAs("nb_measures"));
        assertEquals(0L, (long) hourly.get("3|").getAs("nb_measures"));
        assertNull(hourly.get("3|").getAs("avg_heart_rate"));
        assertEquals(11L, k.hourly().agg(org.apache.spark.sql.functions.sum("nb_measures")).first().getLong(0));

        assertEquals(List.of(KpiTable.TOP_PATIENTS.columnNames()), List.of(k.topPatients().columns()));
        List<Row> top = k.topPatients().orderBy(org.apache.spark.sql.functions.col("nb_alerts").desc(),
                org.apache.spark.sql.functions.col("patient_id")).collectAsList();
        assertEquals(List.of("P00042", "P00050", "P00051", "P01001", "P01042"),
                top.stream().map(r -> (String) r.getAs("patient_id")).collect(Collectors.toList()));
        Row p42 = top.get(0);
        assertEquals(2L, (long) p42.getAs("nb_alerts"));
        assertEquals(84, (int) p42.getAs("min_spo2"));
        assertEquals(155, (int) p42.getAs("max_heart_rate"));
        assertEquals("high_risk", p42.getAs("type"));
    }

    @Test
    void topPatientsIsLimitedTo20() {
        String r = "2026-11-18T08:00:00Z";
        List<Row> rows = new ArrayList<>();
        for (int i = 1; i <= 25; i++) {
            String id = String.format(Locale.ROOT, "%05d", i);
            for (int n = 0; n < i; n++) {   // patient i has i alerts
                rows.add(rec(0, i * 100L + n, r, "P" + id,
                        measure("hr-" + id, "P" + id, "high_risk", n, "2026-11-18T07:59:00Z", 160, 95, 37, 120, 80, false, r)));
            }
        }
        Dataset<Row> cleaned = Transformations.clean(Transformations.parseRaw(spark.createDataFrame(rows, KAFKA_LIKE), DAY));
        List<Row> top = Transformations.topPatients(cleaned, DAY).collectAsList();
        assertEquals(20, top.size());
        long min = top.stream().mapToLong(x -> x.getAs("nb_alerts")).min().orElseThrow();
        assertEquals(6L, min, "patients 25..6 are kept");
    }

    @Test
    void emptyDayGivesZeroKpis() {
        Dataset<Row> empty = spark.createDataFrame(new ArrayList<Row>(), KAFKA_LIKE);
        Dataset<Row> cleaned = Transformations.clean(Transformations.parseRaw(empty, DAY));
        assertEquals(0, cleaned.count());
        Transformations.Kpis k = Transformations.kpis(cleaned, DAY, Timestamp.from(Instant.now()));
        List<Row> summary = k.dailySummary().collectAsList();
        assertEquals(1, summary.size());
        Row s = summary.get(0);
        assertEquals(0L, (long) s.getAs("nb_measures"));
        assertEquals(0, (int) s.getAs("nb_patients"));
        assertEquals(0L, (long) s.getAs("nb_alerts"));
        assertEquals(0L, (long) s.getAs("nb_anomalies"));
        assertEquals(0.0, (double) s.getAs("pct_alert"));
        assertNull(s.getAs("avg_heart_rate"));
        assertEquals(2, k.dailyByType().count());
        assertEquals(0L, k.dailyByType().where("nb_measures <> 0").count());
        assertEquals(24, k.hourly().count());
        assertEquals(0L, k.hourly().where("nb_measures <> 0").count());
        assertEquals(0, k.topPatients().count());
    }

    @Test
    void jsonLinesInputAndDryRun(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("measures.jsonl");
        String r = "2026-11-18T08:00:00Z";
        List<String> lines = List.of(
                normal("hr-00001", "P00001", "high_risk", 1, "2026-11-18T07:59:59Z", r),
                measure("hr-00001", "P00001", "high_risk", 2, "2026-11-18T07:59:59Z", 72, 80, 36.8, 121, 79, false, r),
                "",
                // received the day before (Paris) -> outside the window
                normal("hr-00002", "P00002", "high_risk", 1, "2026-11-17T22:00:00Z", "2026-11-17T22:30:00Z"));
        Files.write(file, lines, StandardCharsets.UTF_8);
        Instant start = JobConfig.dayStart(DAY);
        Instant end = JobConfig.dayStart(DAY.plusDays(1));
        Dataset<Row> records = DailyJob.readJsonLines(spark, file.toString(), start, end);
        assertEquals(2, records.count());
        Dataset<Row> raw = Transformations.parseRaw(records, DAY);
        assertEquals(1, Transformations.clean(raw).where("is_alert").count());
        DailyJob.dryRun(raw, DAY);   // must not throw
    }

    @Test
    void dayWindowFollowsParisTime() {
        assertEquals(Instant.parse("2026-11-17T23:00:00Z"), JobConfig.dayStart(DAY));
        assertEquals(Duration.ofHours(24), Duration.between(JobConfig.dayStart(DAY), JobConfig.dayStart(DAY.plusDays(1))));
        LocalDate springForward = LocalDate.of(2026, 3, 29);
        assertEquals(Duration.ofHours(23),
                Duration.between(JobConfig.dayStart(springForward), JobConfig.dayStart(springForward.plusDays(1))));
        LocalDate fallBack = LocalDate.of(2026, 10, 25);
        assertEquals(Duration.ofHours(25),
                Duration.between(JobConfig.dayStart(fallBack), JobConfig.dayStart(fallBack.plusDays(1))));
    }

    @Test
    void argsParsing() {
        JobArgs a = JobArgs.parse(new String[] {"--day", "2026-11-18"});
        assertEquals(DAY, a.day());
        assertNull(a.inputJson());
        assertFalse(a.dryRun());
        JobArgs b = JobArgs.parse(new String[] {"--day=2026-11-18", "--input-json", "/tmp/x.jsonl", "--dry-run"});
        assertEquals("/tmp/x.jsonl", b.inputJson());
        assertTrue(b.dryRun());
        assertThrows(JobArgs.UsageException.class, () -> JobArgs.parse(new String[] {}));
        assertThrows(JobArgs.UsageException.class, () -> JobArgs.parse(new String[] {"--day", "18/11/2026"}));
        assertThrows(JobArgs.UsageException.class, () -> JobArgs.parse(new String[] {"--day"}));
        assertThrows(JobArgs.UsageException.class, () -> JobArgs.parse(new String[] {"--day", "2026-11-18", "--x"}));
    }

    @Test
    void missingSecretsAreReported() {
        JobConfig cfg = new JobConfig(Map.of("KPI_DB_USER", "kpi_writer"));
        IllegalStateException e = assertThrows(IllegalStateException.class, cfg::requireWriteSecrets);
        assertTrue(e.getMessage().contains("KPI_DB_PASSWORD"));
        assertTrue(e.getMessage().contains("S3_SECRET_KEY"));
        assertFalse(e.getMessage().contains("KPI_DB_USER"));
        assertEquals("vm-kafka-01:9093", cfg.kafkaBootstrap);
        assertEquals("https://vm-cold-02:8333", cfg.s3Endpoint);
    }

    @Test
    void authSecretIsTakenFromTheWorkerEnvironmentInClusterMode() {
        org.apache.spark.SparkConf auth = new org.apache.spark.SparkConf(false).set("spark.authenticate", "true");
        assertEquals("from-worker", DailyJob.authSecretForDriver(auth,
                Map.of("_SPARK_AUTH_SECRET", "from-worker", "SPARK_AUTH_SECRET", "from-env-file")));
        assertEquals("from-env-file", DailyJob.authSecretForDriver(auth, Map.of("SPARK_AUTH_SECRET", "from-env-file")));
        assertNull(DailyJob.authSecretForDriver(auth.clone().set("spark.authenticate.secret", "x"),
                Map.of("_SPARK_AUTH_SECRET", "from-worker")), "conf wins");
        assertNull(DailyJob.authSecretForDriver(new org.apache.spark.SparkConf(false),
                Map.of("_SPARK_AUTH_SECRET", "from-worker")), "auth disabled");
    }
}
