package aeger.vitals;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JwtVerifierTest {

    private static final String ISSUER = "https://192.168.56.10/auth/realms/aeger";

    @TempDir
    static Path dir;
    private static KeyPair keys;
    private static KeyPair otherKeys;
    private static JwtVerifier verifier;

    @BeforeAll
    static void setUp() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        keys = g.generateKeyPair();
        otherKeys = g.generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n"
                + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(keys.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        Path keyFile = dir.resolve("keycloak-realm.pem");
        Files.writeString(keyFile, pem);
        verifier = new JwtVerifier(keyFile.toString(), ISSUER, "medecin");
    }

    private static String token(KeyPair kp, String issuer, Instant exp, List<String> roles) throws Exception {
        JWTClaimsSet claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject("medecin1")
                .expirationTime(Date.from(exp))
                .claim("typ", "Bearer")
                .claim("realm_access", Map.of("roles", roles))
                .build();
        SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
        jwt.sign(new RSASSASigner((RSAPrivateKey) kp.getPrivate()));
        return jwt.serialize();
    }

    private static Instant inOneHour() {
        return Instant.now().plusSeconds(3600);
    }

    @Test
    void jetonValide() throws Exception {
        assertEquals(200, verifier.verify(token(keys, ISSUER, inOneHour(), List.of("medecin", "offline_access"))).status());
    }

    @Test
    void jetonAbsentOuIllisible() {
        assertEquals(401, verifier.verify(null).status());
        assertEquals(401, verifier.verify("abc.def.ghi").status());
    }

    @Test
    void mauvaiseSignature() throws Exception {
        assertEquals(401, verifier.verify(token(otherKeys, ISSUER, inOneHour(), List.of("medecin"))).status());
    }

    @Test
    void jetonExpire() throws Exception {
        assertEquals(401, verifier.verify(token(keys, ISSUER, Instant.now().minusSeconds(120), List.of("medecin"))).status());
    }

    @Test
    void mauvaisEmetteur() throws Exception {
        assertEquals(401, verifier.verify(token(keys, "https://evil/realms/aeger", inOneHour(), List.of("medecin"))).status());
    }

    @Test
    void roleManquant() throws Exception {
        assertEquals(403, verifier.verify(token(keys, ISSUER, inOneHour(), List.of("analyste"))).status());
    }
}
