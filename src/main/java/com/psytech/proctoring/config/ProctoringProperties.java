package com.psytech.proctoring.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration lue depuis application.properties (préfixe "proctoring"). */
@ConfigurationProperties(prefix = "proctoring")
public record ProctoringProperties(
        String jwtSecret,
        String serviceKey,
        Geo geo,
        String corsOrigins,
        boolean trustForwardedFor) {

    public record Geo(String url) {}

    public ProctoringProperties {
        // Pas de longueur minimale imposée : le secret doit simplement être IDENTIQUE à celui de Node.
        if (jwtSecret == null || jwtSecret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET vide : mets la même valeur que JWT_SECRET dans le .env de Node.");
        }
        if (serviceKey == null || serviceKey.isBlank()) {
            throw new IllegalStateException("PROCTORING_SERVICE_KEY vide : mets la même valeur que dans le .env de Node.");
        }
    }
}
