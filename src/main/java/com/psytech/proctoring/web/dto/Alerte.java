package com.psytech.proctoring.web.dto;

/** Une raison qui a fait monter le score de risque. */
public record Alerte(String regle, String detail, int points) {}
