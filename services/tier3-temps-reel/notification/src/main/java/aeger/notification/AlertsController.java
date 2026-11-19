package aeger.notification;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/**
 * API HTTP de notification (contrat § 5) :
 * <ul>
 *   <li>POST /internal/alerts (CN alerting) : corps = alerte → 204</li>
 *   <li>GET /api/alerts/recent (CN nginx + jeton) : 100 dernières alertes, la plus récente en premier</li>
 *   <li>GET /health</li>
 * </ul>
 */
@RestController
public class AlertsController {

    private final AlertHub hub;
    private final ObjectMapper mapper;

    public AlertsController(AlertHub hub, ObjectMapper mapper) {
        this.hub = hub;
        this.mapper = mapper;
    }

    @PostMapping("/internal/alerts")
    public ResponseEntity<?> receive(@RequestBody(required = false) byte[] body) throws IOException {
        JsonNode alert;
        try {
            alert = body == null || body.length == 0 ? null : mapper.readTree(body);
        } catch (IOException e) {
            alert = null;
        }
        if (alert == null || !alert.isObject()) {
            return ResponseEntity.badRequest().body(Map.of("error", "alerte JSON (objet) attendue"));
        }
        JsonNode id = alert.get("alert_id");
        // re-sérialisation compacte : un message WebSocket = une ligne JSON
        hub.publish(id == null || id.isNull() ? null : id.asText(), mapper.writeValueAsString(alert));
        return ResponseEntity.noContent().build();
    }

    @GetMapping(value = "/api/alerts/recent", produces = MediaType.APPLICATION_JSON_VALUE)
    public String recent() {
        return "[" + String.join(",", hub.recentNewestFirst()) + "]";
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
