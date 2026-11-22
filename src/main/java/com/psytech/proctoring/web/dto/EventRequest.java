package com.psytech.proctoring.web.dto;

import com.psytech.proctoring.model.EventType;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/** Corps de POST /api/events. L'invitationId n'est PAS dans le corps : il vient du JWT (impossible d'usurper un autre candidat). */
public record EventRequest(
        @NotNull(message = "type requis") EventType type,
        @Positive(message = "questionId invalide") Long questionId,
        @Pattern(regexp = "^[A-D]$", message = "choix: A, B, C ou D") String choix,
        @Min(value = 0, message = "tempsMs invalide") @Max(value = 3_600_000, message = "tempsMs invalide") Long tempsMs) {}
