package aeger.batch;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.apache.spark.sql.Row;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Copies one day of KPIs into PostgreSQL (database kpi, schema kpi, tables created by the infrastructure
 * with the DDL of CONTRAT.md section 9). Plain JDBC, one transaction: for each table
 * {@code DELETE ... WHERE day = ?} then a batch of INSERTs; either every table is replaced or none.
 */
public final class KpiPostgres {

    private static final Logger LOG = LoggerFactory.getLogger(KpiPostgres.class);

    private KpiPostgres() {
    }

    /**
     * @param rowsByTable rows to write for each table (collected on the driver; at most 1 + 2 + 24 + 20 rows)
     * @return number of rows inserted per PostgreSQL table
     */
    public static Map<String, Integer> replaceDay(String jdbcUrl, String user, String password, LocalDate day,
                                                  Map<KpiTable, List<Row>> rowsByTable) throws SQLException {
        try {
            Class.forName("org.postgresql.Driver");
        } catch (ClassNotFoundException e) {
            throw new SQLException("PostgreSQL JDBC driver not found on the classpath", e);
        }
        Properties props = new Properties();
        props.setProperty("user", user);
        props.setProperty("password", password);
        props.setProperty("ApplicationName", "aeger-daily-job");

        Map<String, Integer> written = new LinkedHashMap<>();
        try (Connection conn = DriverManager.getConnection(jdbcUrl, props)) {
            conn.setAutoCommit(false);
            try {
                for (Map.Entry<KpiTable, List<Row>> e : rowsByTable.entrySet()) {
                    KpiTable table = e.getKey();
                    int deleted = deleteDay(conn, table, day);
                    int inserted = insertRows(conn, table, day, e.getValue());
                    LOG.info("[aeger] postgres {}: {} old row(s) deleted, {} row(s) inserted for {}",
                            table.postgresTable(), deleted, inserted, day);
                    written.put(table.postgresTable(), inserted);
                }
                conn.commit();
            } catch (SQLException | RuntimeException ex) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackError) {
                    ex.addSuppressed(rollbackError);
                }
                throw ex;
            }
        }
        return written;
    }

    private static int deleteDay(Connection conn, KpiTable table, LocalDate day) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table.postgresTable() + " WHERE day = ?")) {
            ps.setObject(1, day);
            return ps.executeUpdate();
        }
    }

    private static int insertRows(Connection conn, KpiTable table, LocalDate day, List<Row> rows) throws SQLException {
        if (rows.isEmpty()) {
            return 0;
        }
        String[] names = table.columnNames();
        String sql = "INSERT INTO " + table.postgresTable() + " (" + String.join(", ", names) + ") VALUES ("
                + "?, ".repeat(names.length - 1) + "?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (Row row : rows) {
                for (int i = 0; i < names.length; i++) {
                    KpiTable.Column col = table.columns().get(i);
                    if (col.name().equals("day")) {
                        ps.setObject(i + 1, day);
                        continue;
                    }
                    Object value = row.get(row.fieldIndex(col.name()));
                    if (value == null) {
                        ps.setNull(i + 1, col.sqlType());
                    } else {
                        ps.setObject(i + 1, value);
                    }
                }
                ps.addBatch();
            }
            int total = 0;
            for (int n : ps.executeBatch()) {
                total += (n == Statement.SUCCESS_NO_INFO) ? 1 : n;
            }
            return total;
        }
    }
}
