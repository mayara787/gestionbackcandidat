package com.psytech.proctoring;

import static org.junit.jupiter.api.Assertions.*;

import com.psytech.proctoring.config.ProctoringProperties;
import com.psytech.proctoring.security.JwtVerifier;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** Fabrique des tokens exactement comme « jsonwebtoken » côté Node (HS256, sub numérique). */
class JwtVerifierTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-secret";
    private final JwtVerifier verifier =
            new JwtVerifier(new ProctoringProperties(SECRET, "cle-de-service-1234", new ProctoringProperties.Geo("https://ipwho.is/"), "*", false));

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String token(String secret, String alg, String payload) throws Exception {
        String h = b64("{\"alg\":\"" + alg + "\",\"typ\":\"JWT\"}");
        String p = b64(payload);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        String sig = Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal((h + "." + p).getBytes(StandardCharsets.US_ASCII)));
        return h + "." + p + "." + sig;
    }

    private static long dans(long secondes) { return Instant.now().getEpochSecond() + secondes; }

    @Test
    void accepteUnTokenCandidatValideAvecSubNumerique() throws Exception {
        String t = token(SECRET, "HS256", "{\"sub\":42,\"role\":\"candidat\",\"iat\":1,\"exp\":" + dans(3600) + "}");
        assertEquals(42L, verifier.invitationIdCandidat(t).orElseThrow());
    }

    @Test
    void refuseUneMauvaiseSignature() throws Exception {
        String t = token("un-autre-secret-un-autre-secret-123456", "HS256", "{\"sub\":42,\"role\":\"candidat\",\"exp\":" + dans(3600) + "}");
        assertTrue(verifier.invitationIdCandidat(t).isEmpty());
    }

    @Test
    void refuseUnTokenExpire() throws Exception {
        String t = token(SECRET, "HS256", "{\"sub\":42,\"role\":\"candidat\",\"exp\":" + dans(-10) + "}");
        assertTrue(verifier.invitationIdCandidat(t).isEmpty());
    }

    @Test
    void refuseUnAutreRole() throws Exception {
        String t = token(SECRET, "HS256", "{\"sub\":1,\"role\":\"entreprise\",\"exp\":" + dans(3600) + "}");
        assertTrue(verifier.invitationIdCandidat(t).isEmpty());
    }

    @Test
    void refuseAlgorithmeNone() {
        String t = b64("{\"alg\":\"none\"}") + "." + b64("{\"sub\":42,\"role\":\"candidat\",\"exp\":" + dans(3600) + "}") + ".";
        assertTrue(verifier.invitationIdCandidat(t).isEmpty());
    }

    @Test
    void refuseDuTexteQuelconque() {
        assertTrue(verifier.invitationIdCandidat("n'importe quoi").isEmpty());
    }
}
