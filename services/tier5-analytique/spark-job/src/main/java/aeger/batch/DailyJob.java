package aeger.batch;

import static org.apache.spark.sql.functions.coalesce;
import static org.apache.spark.sql.functions.col;
import static org.apache.spark.sql.functions.current_timestamp;
import static org.apache.spark.sql.functions.get_json_object;
import static org.apache.spark.sql.functions.length;
import static org.apache.spark.sql.functions.lit;
import static org.apache.spark.sql.functions.monotonically_increasing_id;
import static org.apache.spark.sql.functions.trim;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.spark.SparkConf;
import org.apache.spark.sql.Column;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.storage.StorageLevel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * AEGER nightly job (cold path, CONTRAT.md section 10).
 *
 * <ol>
 *   <li>reads the {@code vitals.raw} records received during the requested day (Europe/Paris) from Kafka;</li>
 *   <li>{@code lake.raw.vitals}: every record, parsed, with its Kafka coordinates;</li>
 *   <li>{@code lake.cleaned.vitals}: valid measures, without duplicates, with is_alert/severity;</li>
 *   <li>{@code lake.kpi.*}: daily_summary, daily_by_type, hourly, top_patients;</li>
 *   <li>replaces the day's rows of the PostgreSQL tables {@code kpi.*} (one transaction).</li>
 * </ol>
 * Every step overwrites only the requested day, so a run can be repeated safely.
 *
 * <p>Exit code: 0 success, 1 failure, 2 bad arguments (the standalone master reports FINISHED / FAILED).
 */
public final class DailyJob {

    private static final Logger LOG = LoggerFactory.getLogger(DailyJob.class);

    private DailyJob() {
    }

    public static void main(String[] args) {
        int rc;
        try {
            run(JobArgs.parse(args), JobConfig.fromEnv());
            rc = 0;
        } catch (JobArgs.UsageException e) {
            LOG.error("[aeger] {} -- {}", e.getMessage(), JobArgs.USAGE);
            rc = 2;
        } catch (Throwable t) {
            LOG.error("[aeger] daily job FAILED", t);
            rc = 1;
        }
        // Explicit exit: in standalone cluster mode the driver state (FINISHED / FAILED) is the exit code of
        // this JVM, and a lingering non-daemon thread must never keep a finished driver alive.
        System.exit(rc);
    }

    public static void run(JobArgs args, JobConfig cfg) throws Exception {
        LocalDate day = args.day();
        Instant start = JobConfig.dayStart(day);
        Instant end = JobConfig.dayStart(day.plusDays(1));
        if (!args.dryRun()) {
            cfg.requireWriteSecrets();
        }
        LOG.info("[aeger] daily job: day={} window=[{} , {}) ({} h, Europe/Paris) source={}{}",
                day, start, end, Duration.between(start, end).toHours(),
                args.inputJson() == null ? "kafka " + cfg.kafkaBootstrap + " topic " + JobConfig.KAFKA_TOPIC
                        : "file " + args.inputJson(),
                args.dryRun() ? " DRY-RUN (nothing written)" : "");
        LOG.info("[aeger] JVM truststore (S3 TLS): {}",
                System.getProperty("javax.net.ssl.trustStore", "(JDK default cacerts)"));

        SparkSession spark = buildSession(cfg, day);
        try {
            Dataset<Row> records = args.inputJson() == null
                    ? readKafka(spark, cfg, start, end)
                    : readJsonLines(spark, args.inputJson(), start, end);
            Dataset<Row> raw = Transformations.parseRaw(records, day);
            if (args.dryRun()) {
                dryRun(raw, day);
            } else {
                process(spark, cfg, raw, day);
            }
        } finally {
            spark.stop();
        }
    }

    // ---------------------------------------------------------------------------------- session

    /**
     * Shared secret for spark.authenticate when it is not in the driver's conf. In standalone cluster mode the
     * worker removes -Dspark.authenticate.secret from the driver command line and passes it in the environment
     * variable _SPARK_AUTH_SECRET, but SparkContext only accepts it from the conf: we copy it back.
     */
    static String authSecretForDriver(SparkConf conf, Map<String, String> env) {
        if (!conf.getBoolean("spark.authenticate", false) || conf.contains("spark.authenticate.secret")) {
            return null;
        }
        for (String name : new String[] {"_SPARK_AUTH_SECRET", "SPARK_AUTH_SECRET"}) {
            String v = env.get(name);
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    /** Iceberg catalog "lake" configured here so that spark-submit stays minimal. */
    static SparkSession buildSession(JobConfig cfg, LocalDate day) {
        String lake = "spark.sql.catalog." + Lake.CATALOG;
        SparkSession.Builder builder = SparkSession.builder();
        String secret = authSecretForDriver(new SparkConf(), System.getenv());
        if (secret != null) {
            builder.config("spark.authenticate.secret", secret);
        }
        return builder
                .appName("aeger-daily-" + day)
                .config("spark.sql.session.timeZone", JobConfig.ZONE.getId())
                .config("spark.sql.shuffle.partitions", "4")
                .config("spark.sql.extensions", "org.apache.iceberg.spark.extensions.IcebergSparkSessionExtensions")
                .config(lake, "org.apache.iceberg.spark.SparkCatalog")
                .config(lake + ".type", "jdbc")
                .config(lake + ".uri", JobConfig.LAKE_JDBC_URL)
                .config(lake + ".jdbc.user", cfg.dbUser())
                .config(lake + ".jdbc.password", cfg.dbPassword())
                .config(lake + ".warehouse", JobConfig.LAKE_WAREHOUSE)
                .config(lake + ".io-impl", "org.apache.iceberg.aws.s3.S3FileIO")
                .config(lake + ".s3.endpoint", cfg.s3Endpoint)
                .config(lake + ".s3.path-style-access", "true")
                .config(lake + ".s3.access-key-id", cfg.s3AccessKey())
                .config(lake + ".s3.secret-access-key", cfg.s3SecretKey())
                .config(lake + ".client.region", JobConfig.S3_REGION)
                .getOrCreate();
    }

    // ------------------------------------------------------------------------------------ input

    /** Kafka batch read of [start, end): records whose Kafka timestamp falls in the day. */
    static Dataset<Row> readKafka(SparkSession spark, JobConfig cfg, Instant start, Instant end) {
        return spark.read().format("kafka")
                .option("kafka.bootstrap.servers", cfg.kafkaBootstrap)
                .option("subscribe", JobConfig.KAFKA_TOPIC)
                .option("startingTimestamp", Long.toString(start.toEpochMilli()))
                .option("endingTimestamp", Long.toString(end.toEpochMilli()))
                // No record at/after the start (empty day, day not started yet, retention expired):
                // start from the end of the partition (= no data) instead of failing.
                .option("startingOffsetsByTimestampStrategy", "latest")
                .option("failOnDataLoss", "false")
                .option("groupIdPrefix", JobConfig.KAFKA_GROUP_ID_PREFIX)
                .option("kafka.security.protocol", "SSL")
                .option("kafka.ssl.keystore.type", "PEM")
                .option("kafka.ssl.keystore.location", JobConfig.KAFKA_KEYSTORE_PEM)
                .option("kafka.ssl.truststore.type", "PEM")
                .option("kafka.ssl.truststore.location", JobConfig.CA_PEM)
                .option("kafka.ssl.endpoint.identification.algorithm", "https")
                .load()
                .select(
                        col("partition").as("kafka_partition"),
                        col("offset").as("kafka_offset"),
                        col("timestamp").as("kafka_timestamp"),
                        col("key").cast("string").as("kafka_key"),
                        col("value").cast("string").as("value"));
    }

    /**
     * Test/demo input: one Kafka value (JSON measure) per line. The Kafka timestamp is simulated with
     * received_at (or now), and the same day window is applied.
     */
    static Dataset<Row> readJsonLines(SparkSession spark, String path, Instant start, Instant end) {
        Column receivedAt = get_json_object(col("value"), "$.received_at").cast("timestamp");
        return spark.read().text(path)
                .where(length(trim(col("value"))).gt(0))
                .select(
                        lit(0).as("kafka_partition"),
                        monotonically_increasing_id().as("kafka_offset"),
                        coalesce(receivedAt, current_timestamp()).as("kafka_timestamp"),
                        get_json_object(col("value"), "$.patient_id").as("kafka_key"),
                        col("value"))
                .where(col("kafka_timestamp").geq(lit(Timestamp.from(start)))
                        .and(col("kafka_timestamp").lt(lit(Timestamp.from(end)))));
    }

    // ------------------------------------------------------------------------------- processing

    static void process(SparkSession spark, JobConfig cfg, Dataset<Row> raw, LocalDate day) throws Exception {
        long t0 = System.nanoTime();
        Lake.ensureTables(spark);
        LOG.info("[aeger] lake ready: catalog {} (JDBC {}), warehouse {}", Lake.CATALOG,
                JobConfig.LAKE_JDBC_URL.replaceAll("\\?.*", ""), JobConfig.LAKE_WAREHOUSE);

        // 1. raw zone: everything that was read, parsed (unreadable records kept with null columns)
        Lake.overwriteDay(raw, Lake.RAW_VITALS, day);
        Dataset<Row> rawDay = Lake.readDay(spark, Lake.RAW_VITALS, day);
        long nRaw = rawDay.count();
        LOG.info("[aeger] raw: {} record(s) read for {} -> {}", nRaw, day, Lake.RAW_VITALS);

        // 2. cleaned zone: valid, deduplicated, with alert flags
        long nValid = Transformations.validate(rawDay).count();
        Lake.overwriteDay(Transformations.clean(rawDay), Lake.CLEANED_VITALS, day);
        Dataset<Row> cleaned = Lake.readDay(spark, Lake.CLEANED_VITALS, day);
        long nClean = cleaned.count();
        LOG.info("[aeger] cleaned: {} measure(s) kept, {} invalid record(s) and {} duplicate(s) removed -> {}",
                nClean, nRaw - nValid, nValid - nClean, Lake.CLEANED_VITALS);

        // 3. KPI zone (lake), then read back what was committed
        Transformations.Kpis kpis = Transformations.kpis(cleaned, day, Timestamp.from(Instant.now()));
        Map<KpiTable, Dataset<Row>> computed = new LinkedHashMap<>();
        computed.put(KpiTable.DAILY_SUMMARY, kpis.dailySummary());
        computed.put(KpiTable.DAILY_BY_TYPE, kpis.dailyByType());
        computed.put(KpiTable.HOURLY, kpis.hourly());
        computed.put(KpiTable.TOP_PATIENTS, kpis.topPatients());
        Map<KpiTable, List<Row>> rows = new LinkedHashMap<>();
        for (Map.Entry<KpiTable, Dataset<Row>> e : computed.entrySet()) {
            KpiTable table = e.getKey();
            Lake.overwriteDay(e.getValue(), table.lakeTable(), day);
            List<Row> written = Lake.readDay(spark, table.lakeTable(), day).collectAsList();
            rows.put(table, written);
            LOG.info("[aeger] kpi: {} row(s) -> {}", written.size(), table.lakeTable());
        }
        logSummary(day, rows.get(KpiTable.DAILY_SUMMARY));

        // 4. serving copy for Grafana
        Map<String, Integer> inserted = KpiPostgres.replaceDay(JobConfig.KPI_JDBC_URL, cfg.dbUser(),
                cfg.dbPassword(), day, rows);
        LOG.info("[aeger] postgres: KPIs of {} replaced (one transaction): {}", day, inserted);
        LOG.info("[aeger] daily job OK for {} in {} s", day, Duration.ofNanos(System.nanoTime() - t0).toSeconds());
    }

    /** --dry-run: same computations in memory, results printed, nothing written anywhere. */
    static void dryRun(Dataset<Row> raw, LocalDate day) {
        Dataset<Row> rawDay = raw.persist(StorageLevel.MEMORY_AND_DISK());
        long nRaw = rawDay.count();
        long nValid = Transformations.validate(rawDay).count();
        Dataset<Row> cleaned = Transformations.clean(rawDay).persist(StorageLevel.MEMORY_AND_DISK());
        long nClean = cleaned.count();
        LOG.info("[aeger] raw: {} record(s) read for {}", nRaw, day);
        LOG.info("[aeger] cleaned: {} measure(s) kept, {} invalid record(s) and {} duplicate(s) removed",
                nClean, nRaw - nValid, nValid - nClean);
        cleaned.orderBy("kafka_timestamp", "bracelet_id", "seq").show(50, false);
        Transformations.Kpis kpis = Transformations.kpis(cleaned, day, Timestamp.from(Instant.now()));
        List<Row> summary = kpis.dailySummary().collectAsList();
        logSummary(day, summary);
        kpis.dailySummary().show(false);
        kpis.dailyByType().show(false);
        kpis.hourly().show(24, false);
        kpis.topPatients().show(20, false);
        LOG.info("[aeger] dry-run OK for {} (nothing written)", day);
    }

    private static void logSummary(LocalDate day, List<Row> summary) {
        if (summary == null || summary.isEmpty()) {
            LOG.warn("[aeger] no daily_summary row for {}", day);
            return;
        }
        Row s = summary.get(0);
        LOG.info("[aeger] summary {}: {} measure(s), {} patient(s), {} alert(s) ({} %), {} anomaly flag(s)",
                day, s.getAs("nb_measures"), s.getAs("nb_patients"), s.getAs("nb_alerts"),
                String.format(java.util.Locale.ROOT, "%.2f", (Double) s.getAs("pct_alert")), s.getAs("nb_anomalies"));
    }
}
