package aeger.notification;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.IOException;
import java.security.cert.X509Certificate;
import java.util.Map;

/**
 * Autorisation par CN du certificat client + jeton OIDC (contrat § 5).
 * Le TLS (server.ssl.client-auth=need) garantit déjà que le certificat est signé par la CA AEGER.
 * <ul>
 *   <li>GET /health : tout certificat valide</li>
 *   <li>POST /internal/alerts : CN alerting</li>
 *   <li>GET /api/alerts/recent : CN nginx + jeton (en-tête Authorization: Bearer)</li>
 *   <li>GET /ws/alerts : CN nginx + jeton (paramètre access_token), vérifié pendant la poignée de main</li>
 * </ul>
 */
@Component
public class ClientCertFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientCertFilter.class);

    private final ObjectMapper mapper;
    private final JwtVerifier jwt;

    public ClientCertFilter(ObjectMapper mapper, JwtVerifier jwt) {
        this.mapper = mapper;
        this.jwt = jwt;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String path = req.getServletPath() + (req.getPathInfo() == null ? "" : req.getPathInfo());
        String method = req.getMethod();
        String cn = clientCn(req);
        if (cn == null) {
            deny(res, 401, "certificat client requis");
            return;
        }

        String requiredCn;
        boolean needsToken;
        if ("GET".equals(method) && "/health".equals(path)) {
            chain.doFilter(req, res);
            return;
        } else if ("POST".equals(method) && "/internal/alerts".equals(path)) {
            requiredCn = "alerting";
            needsToken = false;
        } else if ("GET".equals(method) && ("/api/alerts/recent".equals(path) || "/ws/alerts".equals(path))) {
            requiredCn = "nginx";
            needsToken = true;
        } else {
            deny(res, 404, "route inconnue");
            return;
        }

        if (!requiredCn.equals(cn)) {
            log.warn("Accès refusé : CN '{}' sur {} {}", cn, method, path);
            deny(res, 403, "CN non autorisé : " + cn);
            return;
        }
        if (needsToken) {
            JwtVerifier.Verdict v = jwt.verify(token(req));
            if (!v.ok()) {
                log.info("Jeton refusé sur {} : {}", path, v.error());
                if (v.status() == 401) {
                    res.setHeader("WWW-Authenticate", "Bearer error=\"invalid_token\"");
                }
                deny(res, v.status(), v.error());
                return;
            }
        }
        chain.doFilter(req, res);
    }

    /** Jeton : en-tête « Authorization: Bearer … », sinon paramètre access_token (WebSocket). */
    private static String token(HttpServletRequest req) {
        String h = req.getHeader("Authorization");
        if (h != null && h.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return h.substring(7).trim();
        }
        return req.getParameter("access_token");
    }

    /** CN du certificat client présenté pendant la poignée de main TLS (null si absent). */
    static String clientCn(HttpServletRequest req) {
        X509Certificate[] chain = (X509Certificate[]) req.getAttribute("jakarta.servlet.request.X509Certificate");
        if (chain == null || chain.length == 0) {
            return null;
        }
        try {
            LdapName name = new LdapName(chain[0].getSubjectX500Principal().getName());
            for (Rdn rdn : name.getRdns()) {
                if ("CN".equalsIgnoreCase(rdn.getType())) {
                    return rdn.getValue().toString();
                }
            }
        } catch (InvalidNameException e) {
            return null;
        }
        return null;
    }

    private void deny(HttpServletResponse res, int status, String message) throws IOException {
        res.setStatus(status);
        res.setContentType("application/json;charset=UTF-8");
        mapper.writeValue(res.getOutputStream(), Map.of("error", message));
    }
}
