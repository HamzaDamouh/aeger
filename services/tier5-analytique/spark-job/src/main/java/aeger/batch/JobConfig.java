package aeger.batch;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

/**
 * Runtime configuration (CONTRAT.md sections 2, 8, 9, 10). Values come from the environment of the
 * driver process; in standalone cluster mode the driver runs inside the Spark worker container, so
 * these variables must be set on the worker (spark.env).
 */
public final class JobConfig {

    /** Calendar used to define "a day" (Kafka window, hour of day). */
    public static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    public static final String SECRETS_DIR = "/opt/aeger/secrets";
    public static final String CA_PEM = SECRETS_DIR + "/ca.pem";
    public static final String KAFKA_KEYSTORE_PEM = SECRETS_DIR + "/spark.pem";

    public static final String KAFKA_TOPIC = "vitals.raw";
    public static final String KAFKA_GROUP_ID_PREFIX = "aeger-spark";

    public static final String LAKE_JDBC_URL =
            "jdbc:postgresql://vm-db-01:5432/kpi?currentSchema=lake&sslmode=verify-full&sslrootcert=" + CA_PEM;
    public static final String KPI_JDBC_URL =
            "jdbc:postgresql://vm-db-01:5432/kpi?currentSchema=kpi&sslmode=verify-full&sslrootcert=" + CA_PEM;
    public static final String LAKE_WAREHOUSE = "s3://aeger-lake/warehouse";
    public static final String S3_REGION = "us-east-1";

    public final String kafkaBootstrap;
    public final String s3Endpoint;
    private final String dbUser;
    private final String dbPassword;
    private final String s3AccessKey;
    private final String s3SecretKey;

    JobConfig(Map<String, String> env) {
        this.kafkaBootstrap = env.getOrDefault("KAFKA_BOOTSTRAP", "vm-kafka-01:9093");
        this.s3Endpoint = env.getOrDefault("S3_ENDPOINT", "https://vm-cold-02:8333");
        this.dbUser = env.get("KPI_DB_USER");
        this.dbPassword = env.get("KPI_DB_PASSWORD");
        this.s3AccessKey = env.get("S3_ACCESS_KEY");
        this.s3SecretKey = env.get("S3_SECRET_KEY");
    }

    public static JobConfig fromEnv() {
        return new JobConfig(System.getenv());
    }

    /** Fails fast (before any Spark work) when a secret needed for writing is missing. */
    public void requireWriteSecrets() {
        StringBuilder missing = new StringBuilder();
        if (isBlank(dbUser)) missing.append(" KPI_DB_USER");
        if (isBlank(dbPassword)) missing.append(" KPI_DB_PASSWORD");
        if (isBlank(s3AccessKey)) missing.append(" S3_ACCESS_KEY");
        if (isBlank(s3SecretKey)) missing.append(" S3_SECRET_KEY");
        if (missing.length() > 0) {
            throw new IllegalStateException("missing environment variable(s):" + missing
                    + " (they must be set in the Spark worker container, see spark.env)");
        }
    }

    public String dbUser() { return nullToEmpty(dbUser); }
    public String dbPassword() { return nullToEmpty(dbPassword); }
    public String s3AccessKey() { return nullToEmpty(s3AccessKey); }
    public String s3SecretKey() { return nullToEmpty(s3SecretKey); }

    /** Start of the given day in Europe/Paris (handles DST: a day can last 23 or 25 hours). */
    public static Instant dayStart(LocalDate day) {
        return day.atStartOfDay(ZONE).toInstant();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }
}
