package aeger.vitals;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Deux pools de connexions (Hikari, 3 connexions maxi) sur la même base « vitals » :
 * <ul>
 *   <li>writer : rôle vitals_writer (INSERT seul), utilisé par le consommateur Kafka ;</li>
 *   <li>reader : rôle vitals_reader (SELECT seul), utilisé par l'API.</li>
 * </ul>
 * Les pools sont créés à la première utilisation : le service démarre même si la base est injoignable.
 */
@Configuration
public class DataSourceConfig {

    private static final Logger log = LoggerFactory.getLogger(DataSourceConfig.class);

    @Bean(destroyMethod = "close")
    @ConfigurationProperties("aeger.db.writer")
    public HikariDataSource writerDataSource() {
        return new HikariDataSource();
    }

    @Bean(destroyMethod = "close")
    @ConfigurationProperties("aeger.db.reader")
    public HikariDataSource readerDataSource() {
        return new HikariDataSource();
    }

    @Bean
    public JdbcTemplate readerJdbc(@Qualifier("readerDataSource") HikariDataSource reader,
                                   @Qualifier("writerDataSource") HikariDataSource writer) {
        warnIfMissing("writer", writer);
        warnIfMissing("reader", reader);
        return new JdbcTemplate((DataSource) reader);
    }

    private static void warnIfMissing(String name, HikariDataSource ds) {
        log.info("Base {} : {} (utilisateur {})", name, ds.getJdbcUrl(), ds.getUsername());
        if (ds.getUsername() == null || ds.getUsername().isBlank()) {
            log.error("Utilisateur de base « {} » non défini : variables DB_{}_USER / DB_{}_PASSWORD absentes"
                    + " (ni dans l'environnement ni dans vitals.env)", name, name.toUpperCase(), name.toUpperCase());
        }
    }
}
