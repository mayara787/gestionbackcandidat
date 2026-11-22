package com.psytech.proctoring.security;

import com.psytech.proctoring.config.ProctoringProperties;
import com.psytech.proctoring.web.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Deux profils d'accès :
 *  - Candidat (Flutter) : JWT émis par Node  -> POST /api/sessions, POST /api/events
 *  - Service (Node)     : en-tête X-Service-Key -> GET /api/sessions/{id}/report
 * Un candidat ne peut donc jamais lire son propre rapport de risque.
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    public static final String ATTR_INVITATION = "invitationId";
    private static final Pattern RAPPORT = Pattern.compile("^/api/sessions/\\d+/report$");

    private final JwtVerifier jwt;
    private final byte[] serviceKey;

    public AuthInterceptor(JwtVerifier jwt, ProctoringProperties props) {
        this.jwt = jwt;
        this.serviceKey = props.serviceKey().getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if ("OPTIONS".equals(req.getMethod())) return true; // pré-vol CORS
        String path = req.getRequestURI();
        if (path.equals("/api/health")) return true;

        if (RAPPORT.matcher(path).matches()) {
            String fournie = req.getHeader("X-Service-Key");
            if (fournie == null || !MessageDigest.isEqual(serviceKey, fournie.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(HttpStatus.UNAUTHORIZED, "Clé de service invalide.");
            }
            return true;
        }

        String header = req.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Authentification requise.");
        }
        Long invitationId = jwt.invitationIdCandidat(header.substring(7))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "Token invalide ou expiré."));
        req.setAttribute(ATTR_INVITATION, invitationId);
        return true;
    }
}
