package com.psytech.proctoring.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psytech.proctoring.config.ProctoringProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Vérifie un JWT HS256 émis par le backend Node (librairie « jsonwebtoken », même JWT_SECRET).
 * Implémentation volontairement courte : Node met un identifiant NUMÉRIQUE dans "sub",
 * ce que les librairies JWT Java typées refusent parfois.
 */
@Component
public class JwtVerifier {

    private static final ObjectMapper JSON = new ObjectMapper();
    private final byte[] secret;

    public JwtVerifier(ProctoringProperties props) {
        this.secret = props.jwtSecret().getBytes(StandardCharsets.UTF_8);
    }

    /** @return l'invitationId (claim "sub") si le token est valide, signé, non expiré et de rôle "candidat". */
    public Optional<Long> invitationIdCandidat(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 3) return Optional.empty();

            JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
            if (!"HS256".equals(header.path("alg").asText())) return Optional.empty(); // refuse "none" et les autres algos

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            byte[] attendu = mac.doFinal((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            byte[] recu = Base64.getUrlDecoder().decode(parts[2]);
            if (!MessageDigest.isEqual(attendu, recu)) return Optional.empty();

            JsonNode payload = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            JsonNode exp = payload.get("exp");
            if (exp == null || !exp.canConvertToLong() || exp.asLong() <= Instant.now().getEpochSecond()) return Optional.empty();
            if (!"candidat".equals(payload.path("role").asText())) return Optional.empty();

            JsonNode sub = payload.get("sub");
            if (sub == null) return Optional.empty();
            long id = sub.isNumber() ? sub.asLong() : Long.parseLong(sub.asText());
            return id > 0 ? Optional.of(id) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
