package aeger.notification;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.Collection;
import java.util.Date;
import java.util.Map;

/**
 * Vérification HORS LIGNE du jeton Keycloak (contrat § 5) : signature RS256 avec la clé publique
 * du realm (fichier PEM), expiration, émetteur et rôle requis dans realm_access.roles.
 * Aucun appel réseau vers Keycloak.
 * <p>
 * La clé est relue automatiquement si le fichier change (rotation par Vault Agent).
 */
@Component
public class JwtVerifier {

    private static final Logger log = LoggerFactory.getLogger(JwtVerifier.class);
    private static final long CLOCK_SKEW_SECONDS = 30;

    /** Verdict : status 200 = jeton accepté, 401 = invalide, 403 = rôle manquant, 503 = clé illisible. */
    public record Verdict(int status, String error) {
        static final Verdict OK = new Verdict(200, null);

        public boolean ok() {
            return status == 200;
        }
    }

    private final Path keyFile;
    private final String issuer;
    private final String requiredRole;
    private volatile RSAPublicKey key;
    private volatile long keyMtime = -1;

    public JwtVerifier(@Value("${aeger.jwt.public-key}") String keyFile,
                       @Value("${aeger.jwt.issuer}") String issuer,
                       @Value("${aeger.jwt.required-role}") String requiredRole) {
        this.keyFile = Path.of(keyFile);
        this.issuer = issuer;
        this.requiredRole = requiredRole;
        log.info("JWT : clé {}, émetteur {}, rôle requis {}", keyFile, issuer, requiredRole);
    }

    public Verdict verify(String token) {
        if (token == null || token.isBlank()) {
            return new Verdict(401, "jeton manquant");
        }
        RSAPublicKey publicKey;
        try {
            publicKey = publicKey();
        } catch (Exception e) {
            log.error("Clé publique Keycloak illisible ({}) : {}", keyFile, e.toString());
            return new Verdict(503, "clé publique Keycloak indisponible");
        }
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!JWSAlgorithm.RS256.equals(jwt.getHeader().getAlgorithm())) {
                return new Verdict(401, "algorithme non accepté (RS256 attendu)");
            }
            if (!jwt.verify(new RSASSAVerifier(publicKey))) {
                return new Verdict(401, "signature invalide");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Instant now = Instant.now();
            Date exp = claims.getExpirationTime();
            if (exp == null || exp.toInstant().plusSeconds(CLOCK_SKEW_SECONDS).isBefore(now)) {
                return new Verdict(401, "jeton expiré");
            }
            Date nbf = claims.getNotBeforeTime();
            if (nbf != null && nbf.toInstant().minusSeconds(CLOCK_SKEW_SECONDS).isAfter(now)) {
                return new Verdict(401, "jeton pas encore valide");
            }
            if (!issuer.equals(claims.getIssuer())) {
                return new Verdict(401, "émetteur inattendu : " + claims.getIssuer());
            }
            Object typ = claims.getClaim("typ");
            if (typ != null && !"Bearer".equals(typ)) {
                return new Verdict(401, "jeton d'accès attendu (typ=" + typ + ")");
            }
            Map<String, Object> realmAccess = claims.getJSONObjectClaim("realm_access");
            Object roles = realmAccess == null ? null : realmAccess.get("roles");
            if (!(roles instanceof Collection<?> list) || !list.contains(requiredRole)) {
                return new Verdict(403, "rôle " + requiredRole + " requis");
            }
            return Verdict.OK;
        } catch (Exception e) {
            return new Verdict(401, "jeton illisible");
        }
    }

    /** Clé publique RSA (PEM « PUBLIC KEY » ou certificat), rechargée si le fichier a changé. */
    private RSAPublicKey publicKey() throws Exception {
        long mtime = Files.getLastModifiedTime(keyFile).toMillis();
        if (key == null || mtime != keyMtime) {
            synchronized (this) {
                if (key == null || mtime != keyMtime) {
                    key = readKey(Files.readString(keyFile, StandardCharsets.US_ASCII));
                    keyMtime = mtime;
                    log.info("Clé publique Keycloak chargée depuis {}", keyFile);
                }
            }
        }
        return key;
    }

    static RSAPublicKey readKey(String pem) throws Exception {
        PublicKey k;
        if (pem.contains("BEGIN CERTIFICATE")) {
            k = CertificateFactory.getInstance("X.509")
                    .generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)))
                    .getPublicKey();
        } else {
            String b64 = pem.replaceAll("-----(BEGIN|END) PUBLIC KEY-----", "").replaceAll("\\s", "");
            k = KeyFactory.getInstance("RSA").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b64)));
        }
        if (!(k instanceof RSAPublicKey rsa)) {
            throw new IllegalArgumentException("clé RSA attendue");
        }
        return rsa;
    }
}
