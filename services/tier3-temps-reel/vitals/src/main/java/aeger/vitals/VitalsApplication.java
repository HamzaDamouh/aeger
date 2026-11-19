package aeger.vitals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Service vitals : écrit chaque mesure de vitals.raw dans TimescaleDB (table measures)
 * et expose l'historique aux médecins (liste des patients, courbes moyennées).
 */
@SpringBootApplication
@EnableScheduling
public class VitalsApplication {

    public static void main(String[] args) {
        Secrets.awaitFiles("vitals");
        SpringApplication.run(VitalsApplication.class, args);
    }
}
