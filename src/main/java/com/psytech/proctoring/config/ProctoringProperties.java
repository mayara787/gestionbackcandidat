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
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("JWT_SECRET manquant ou trop court (32 caractères minimum, même valeur que Node).");
        }
        if (serviceKey == null || serviceKey.length() < 16) {
            throw new IllegalStateException("PROCTORING_SERVICE_KEY manquant ou trop court (16 caractères minimum).");
        }
    }
}
