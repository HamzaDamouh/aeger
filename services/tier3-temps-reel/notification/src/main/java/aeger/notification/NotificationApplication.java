package aeger.notification;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Service notification : reçoit les alertes d'alerting (POST /internal/alerts), garde les
 * 100 dernières en mémoire et les pousse en WebSocket (/ws/alerts) aux médecins connectés.
 */
@SpringBootApplication
public class NotificationApplication {

    public static void main(String[] args) {
        Secrets.awaitFiles("notification");
        SpringApplication.run(NotificationApplication.class, args);
    }
}
