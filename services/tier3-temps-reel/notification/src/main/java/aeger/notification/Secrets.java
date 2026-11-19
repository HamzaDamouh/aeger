package aeger.notification;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Vérification des fichiers de secrets AVANT le démarrage de Spring.
 * Vault Agent peut démarrer en même temps que le service : on attend donc
 * jusqu'à AEGER_SECRETS_WAIT_SECONDS (120 s par défaut) puis on s'arrête
 * avec un message lisible (code de sortie 2).
 */
final class Secrets {

    private Secrets() {
    }

    static void awaitFiles(String defaultService) {
        String dir = env("AEGER_SECRETS_DIR", "/opt/aeger/secrets");
        String service = env("SERVICE_NAME", defaultService);
        int waitSeconds = Integer.parseInt(env("AEGER_SECRETS_WAIT_SECONDS", "120"));
        List<Path> files = List.of(Path.of(dir, service + ".pem"), Path.of(dir, "ca.pem"));

        long deadline = System.currentTimeMillis() + waitSeconds * 1000L;
        long nextLog = 0;
        while (true) {
            List<Path> missing = files.stream().filter(p -> !Files.isReadable(p)).toList();
            if (missing.isEmpty()) {
                return;
            }
            long now = System.currentTimeMillis();
            if (now >= deadline) {
                System.err.println(LocalDateTime.now() + " ERREUR FATALE [" + service + "] secrets absents ou illisibles : "
                        + missing + " (AEGER_SECRETS_DIR=" + dir + "). Vérifier le montage du dossier de secrets,"
                        + " Vault Agent et les droits de lecture pour l'utilisateur du conteneur.");
                System.exit(2);
            }
            if (now >= nextLog) {
                System.err.println(LocalDateTime.now() + " [" + service + "] en attente des secrets " + missing + " ...");
                nextLog = now + 10_000;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                System.exit(2);
            }
        }
    }

    private static String env(String name, String defaultValue) {
        String v = System.getenv(name);
        return v == null || v.isBlank() ? defaultValue : v;
    }
}
