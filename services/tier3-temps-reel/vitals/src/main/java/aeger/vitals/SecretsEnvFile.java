package aeger.vitals;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lit le fichier de secrets &lt;AEGER_SECRETS_DIR&gt;/&lt;SERVICE_NAME&gt;.env (format CLE=valeur, écrit par
 * Vault Agent) et l'ajoute aux propriétés Spring avec la priorité la plus BASSE :
 * une vraie variable d'environnement (ex. env_file de docker compose) l'emporte toujours.
 * Fichier absent = rien à faire.
 */
public class SecretsEnvFile implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment env, SpringApplication application) {
        String dir = env.getProperty("AEGER_SECRETS_DIR", "/opt/aeger/secrets");
        String service = env.getProperty("SERVICE_NAME", "vitals");
        Path file = Path.of(dir, service + ".env");
        if (!Files.isReadable(file)) {
            return;
        }
        try {
            Map<String, Object> values = parse(Files.readAllLines(file, StandardCharsets.UTF_8));
            env.getPropertySources().addLast(new MapPropertySource("secrets-env-file", values));
        } catch (IOException e) {
            System.err.println("Lecture de " + file + " impossible : " + e);
        }
    }

    /** Lignes « CLE=valeur » ; ignore les commentaires, accepte « export » et les guillemets. */
    static Map<String, Object> parse(List<String> lines) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String raw : lines) {
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("export ")) {
                line = line.substring(7).strip();
            }
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = line.substring(0, eq).strip();
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && (value.startsWith("\"") && value.endsWith("\"")
                    || value.startsWith("'") && value.endsWith("'"))) {
                value = value.substring(1, value.length() - 1);
            }
            values.put(key, value);
        }
        return values;
    }
}
