package aeger.alerting;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Service alerting : lit chaque mesure du topic Kafka vitals.raw, applique les seuils
 * du contrat (§ 4) et transmet chaque alerte à notification (POST /internal/alerts, mTLS).
 * Pas de serveur HTTP.
 */
@SpringBootApplication
@EnableScheduling
public class AlertingApplication {

    public static void main(String[] args) {
        Secrets.awaitFiles("alerting");
        SpringApplication.run(AlertingApplication.class, args);
    }
}
