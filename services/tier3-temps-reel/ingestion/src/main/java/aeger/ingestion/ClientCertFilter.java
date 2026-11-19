package aeger.ingestion;

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
 * Autorisation par CN du certificat client (contrat § 5).
 * Le TLS (server.ssl.client-auth=need) garantit déjà que le certificat est signé par la CA AEGER ;
 * ici on vérifie seulement QUI appelle quelle route.
 * <ul>
 *   <li>GET /health : tout certificat valide</li>
 *   <li>POST /measures : CN nginx uniquement</li>
 * </ul>
 */
@Component
public class ClientCertFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ClientCertFilter.class);

    private final ObjectMapper mapper;

    public ClientCertFilter(ObjectMapper mapper) {
        this.mapper = mapper;
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
        if ("GET".equals(method) && "/health".equals(path)) {
            chain.doFilter(req, res);
            return;
        }
        if ("POST".equals(method) && "/measures".equals(path)) {
            if ("nginx".equals(cn)) {
                chain.doFilter(req, res);
            } else {
                log.warn("Accès refusé : CN '{}' sur {} {}", cn, method, path);
                deny(res, 403, "CN non autorisé : " + cn);
            }
            return;
        }
        deny(res, 404, "route inconnue");
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
