package aeger.ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Service ingestion : reçoit les mesures des bracelets (MQTT pour les haut risque,
 * HTTPS via NGINX pour les standards), les valide, retire les doublons et les écrit
 * dans le topic Kafka vitals.raw (clé = patient_id).
 */
@SpringBootApplication
@EnableScheduling
public class IngestionApplication {

    public static void main(String[] args) {
        // Les certificats sont écrits par Vault Agent : on attend qu'ils soient présents
        // pour échouer avec un message clair plutôt qu'avec une pile d'erreurs Spring.
        Secrets.awaitFiles("ingestion");
        SpringApplication.run(IngestionApplication.class, args);
    }
}
