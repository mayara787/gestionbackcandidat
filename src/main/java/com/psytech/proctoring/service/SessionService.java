package com.psytech.proctoring.service;

import com.psytech.proctoring.model.ProctorEvent;
import com.psytech.proctoring.model.ProctoringSession;
import com.psytech.proctoring.repository.EventRepository;
import com.psytech.proctoring.repository.SessionRepository;
import com.psytech.proctoring.web.dto.EventRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

@Service
public class SessionService {

    private final SessionRepository sessions;
    private final EventRepository events;
    private final GeoService geo;

    public SessionService(SessionRepository sessions, EventRepository events, GeoService geo) {
        this.sessions = sessions;
        this.events = events;
        this.geo = geo;
    }

    // Pas de @Transactional : chaque appel au repository a sa propre transaction, ce qui permet de rattraper
    // proprement une création simultanée de la même session (contrainte d'unicité).

    /** Démarre (ou retrouve) la session d'un candidat et mémorise son pays de départ. Idempotent. */
    public ProctoringSession demarrer(Long invitationId, String ip) {
        ProctoringSession s = trouverOuCreer(invitationId);
        if (s.getPaysDepart() == null) {
            s.setIpDepart(ip);
            s.setDernierIp(ip);
            geo.pays(ip).ifPresent(p -> {
                s.setPaysDepart(p);
                s.setDernierPays(p);
            });
            sessions.save(s);
        }
        return s;
    }

    /** Enregistre un événement. La session est créée à la volée si le démarrage n'a pas pu être enregistré. */
    public void enregistrer(Long invitationId, EventRequest req, String ip) {
        ProctoringSession s = trouverOuCreer(invitationId);
        if (s.getIpDepart() == null) {
            s.setIpDepart(ip);
            geo.pays(ip).ifPresent(s::setPaysDepart);
        }
        // Géolocalisation seulement quand l'IP change (évite un appel externe à chaque événement).
        if (ip != null && !ip.equals(s.getDernierIp())) {
            geo.pays(ip).ifPresent(s::setDernierPays);
        }
        if (ip != null) s.setDernierIp(ip);
        if (s.getDernierPays() == null) s.setDernierPays(s.getPaysDepart());
        sessions.save(s);

        events.save(new ProctorEvent(invitationId, req.type(), req.questionId(), req.choix(), req.tempsMs()));
    }

    private ProctoringSession trouverOuCreer(Long invitationId) {
        return sessions.findByInvitationId(invitationId).orElseGet(() -> {
            try {
                return sessions.saveAndFlush(new ProctoringSession(invitationId));
            } catch (DataIntegrityViolationException concurrente) {
                // deux requêtes simultanées ont créé la session : on reprend celle qui existe
                return sessions.findByInvitationId(invitationId).orElseThrow(() -> concurrente);
            }
        });
    }
}
