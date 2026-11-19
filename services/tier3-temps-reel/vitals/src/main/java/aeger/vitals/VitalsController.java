package aeger.vitals;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * API de consultation (contrat § 5), appelée via NGINX (CN nginx + jeton médecin) :
 * <ul>
 *   <li>GET /api/vitals/patients?q=&amp;limit=50</li>
 *   <li>GET /api/vitals/patients/{id}/history?hours=168 (1 ≤ hours ≤ 168)</li>
 *   <li>GET /health</li>
 * </ul>
 */
@RestController
public class VitalsController {

    private static final Logger log = LoggerFactory.getLogger(VitalsController.class);
    private static final Pattern PATIENT_ID = Pattern.compile("^P\\d{5}$");

    private final VitalsRepository repository;
    private final Stats stats;

    public VitalsController(VitalsRepository repository, Stats stats) {
        this.repository = repository;
        this.stats = stats;
    }

    @GetMapping("/api/vitals/patients")
    public ResponseEntity<?> patients(@RequestParam(defaultValue = "") String q,
                                      @RequestParam(defaultValue = "50") int limit) {
        stats.apiRequests.increment();
        if (q.length() > 32) {
            return badRequest("q trop long (32 caractères maxi)");
        }
        int boundedLimit = Math.max(1, Math.min(limit, 500));
        return ResponseEntity.ok(repository.patients(q.strip(), boundedLimit));
    }

    @GetMapping("/api/vitals/patients/{id}/history")
    public ResponseEntity<?> history(@PathVariable String id, @RequestParam(defaultValue = "168") int hours) {
        stats.apiRequests.increment();
        if (!PATIENT_ID.matcher(id).matches()) {
            return badRequest("identifiant patient invalide (P + 5 chiffres)");
        }
        if (hours < 1 || hours > 168) {
            return badRequest("hours doit être compris entre 1 et 168");
        }
        return ResponseEntity.ok(repository.history(id, hours));
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<?> badParameter(MethodArgumentTypeMismatchException e) {
        return badRequest("paramètre invalide : " + e.getName());
    }

    @ExceptionHandler(DataAccessException.class)
    public ResponseEntity<?> databaseDown(DataAccessException e) {
        log.warn("Lecture en base impossible : {}", e.getMostSpecificCause().toString());
        return ResponseEntity.status(503).body(Map.of("error", "base de données indisponible"));
    }

    private static ResponseEntity<?> badRequest(String message) {
        return ResponseEntity.badRequest().body(Map.of("error", message));
    }
}
