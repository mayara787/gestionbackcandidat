package com.psytech.proctoring.model;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "proctor_event")
public class ProctorEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long invitationId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType type;

    private Long questionId;

    @Column(length = 1)
    private String choix;

    /** Temps passé sur la question avant la première réponse (ms). */
    private Long tempsMs;

    /** Horodatage posé par le SERVEUR : on ne fait pas confiance à l'horloge du téléphone. */
    @Column(nullable = false)
    private Instant occurredAt = Instant.now();

    protected ProctorEvent() {}

    public ProctorEvent(Long invitationId, EventType type, Long questionId, String choix, Long tempsMs) {
        this.invitationId = invitationId;
        this.type = type;
        this.questionId = questionId;
        this.choix = choix;
        this.tempsMs = tempsMs;
    }

    public Long getId() { return id; }
    public Long getInvitationId() { return invitationId; }
    public EventType getType() { return type; }
    public Long getQuestionId() { return questionId; }
    public String getChoix() { return choix; }
    public Long getTempsMs() { return tempsMs; }
    public Instant getOccurredAt() { return occurredAt; }
}
