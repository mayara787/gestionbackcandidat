package com.psytech.proctoring.web;

import com.psytech.proctoring.config.ProctoringProperties;
import com.psytech.proctoring.model.ProctoringSession;
import com.psytech.proctoring.repository.EventRepository;
import com.psytech.proctoring.repository.SessionRepository;
import com.psytech.proctoring.security.AuthInterceptor;
import com.psytech.proctoring.service.ScoringService;
import com.psytech.proctoring.service.SessionService;
import com.psytech.proctoring.web.dto.EventRequest;
import com.psytech.proctoring.web.dto.RiskReport;
import com.psytech.proctoring.web.dto.SessionResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class ProctoringController {

    private static final Logger log = LoggerFactory.getLogger(ProctoringController.class);

    private final SessionService sessionService;
    private final ScoringService scoring;
    private final SessionRepository sessions;
    private final EventRepository events;
    private final ProctoringProperties props;

    public ProctoringController(SessionService sessionService, ScoringService scoring,
                                SessionRepository sessions, EventRepository events, ProctoringProperties props) {
        this.sessionService = sessionService;
        this.scoring = scoring;
        this.sessions = sessions;
        this.events = events;
        this.props = props;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok", "service", "proctoring-service");
    }

    /** Flutter : démarre la surveillance (début du test). */
    @PostMapping("/sessions")
    public SessionResponse demarrer(HttpServletRequest req) {
        Long invitationId = (Long) req.getAttribute(AuthInterceptor.ATTR_INVITATION);
        log.info("[2/4 CONTROLEUR] POST /sessions : démarrage de la surveillance, invitation n°{}, ip={}", invitationId, ipClient(req));
        ProctoringSession s = sessionService.demarrer(invitationId, ipClient(req));
        return new SessionResponse(s.getInvitationId(), s.getPaysDepart());
    }

    /** Flutter : envoie un événement (sortie de l'app, capture, réponse...). */
    @PostMapping("/events")
    public ResponseEntity<Void> evenement(@Valid @RequestBody EventRequest body, HttpServletRequest req) {
        Long invitationId = (Long) req.getAttribute(AuthInterceptor.ATTR_INVITATION);
        log.info("[2/4 CONTROLEUR] POST /events : type={} question={} choix={} tempsMs={} (invitation n°{}, ip={})",
                body.type(), body.questionId(), body.choix(), body.tempsMs(), invitationId, ipClient(req));
        sessionService.enregistrer(invitationId, body, ipClient(req));
        return ResponseEntity.accepted().build();
    }

    /** Node (clé de service) : rapport de risque d'un candidat. */
    @GetMapping("/sessions/{invitationId}/report")
    public RiskReport rapport(@PathVariable Long invitationId) {
        ProctoringSession s = sessions.findByInvitationId(invitationId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Aucune surveillance enregistrée pour ce candidat."));
        var evts = events.findByInvitationIdOrderByOccurredAtAscIdAsc(invitationId);
        log.info("[RAPPORT] GET /sessions/{}/report : {} événement(s) trouvé(s) en base, calcul du score...", invitationId, evts.size());
        RiskReport rapport = scoring.calculer(s, evts);
        log.info("[RAPPORT] invitation n°{} -> score {}/100, niveau {}, {} alerte(s)",
                invitationId, rapport.scoreRisque(), rapport.niveau(), rapport.alertes().size());
        return rapport;
    }

    private String ipClient(HttpServletRequest req) {
        if (props.trustForwardedFor()) {
            String xff = req.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
