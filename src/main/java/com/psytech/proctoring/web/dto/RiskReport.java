package com.psytech.proctoring.web.dto;

import java.util.List;

/** Rapport renvoyé à Node (puis affiché aux RH). */
public record RiskReport(
        Long invitationId,
        int scoreRisque,
        String niveau,
        List<Alerte> alertes,
        int nbEvenements,
        String paysDepart,
        String paysFin) {}
