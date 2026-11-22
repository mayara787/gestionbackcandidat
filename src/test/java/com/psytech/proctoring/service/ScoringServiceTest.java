package com.psytech.proctoring.service;

import static org.junit.jupiter.api.Assertions.*;

import com.psytech.proctoring.model.EventType;
import com.psytech.proctoring.model.ProctorEvent;
import com.psytech.proctoring.model.ProctoringSession;
import com.psytech.proctoring.web.dto.RiskReport;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ScoringServiceTest {

    private final ScoringService scoring = new ScoringService(null); // le repo n'est pas utilisé par la version pure
    private static final Function<Long, List<Long>> PERSONNE = q -> List.of();

    private static ProctorEvent ev(EventType t, Long q, String choix, Long ms) {
        return new ProctorEvent(1L, t, q, choix, ms);
    }

    private static ProctoringSession session(String depart, String fin) {
        ProctoringSession s = new ProctoringSession(1L);
        s.setPaysDepart(depart);
        s.setDernierPays(fin);
        return s;
    }

    private RiskReport calcul(List<ProctorEvent> evts, ProctoringSession s) {
        return scoring.calculer(s, evts, PERSONNE);
    }

    @Test
    void candidatNormalAScoreZero() {
        List<ProctorEvent> e = new ArrayList<>();
        String[] choix = {"A", "C", "B", "D", "B", "A"};
        for (int i = 0; i < 6; i++) e.add(ev(EventType.ANSWER, (long) i + 1, choix[i], 15_000L));
        RiskReport r = calcul(e, session("TN", "TN"));
        assertEquals(0, r.scoreRisque());
        assertEquals("FAIBLE", r.niveau());
        assertTrue(r.alertes().isEmpty());
    }

    @Test
    void exempleDuCours_deuxSortiesUneCaptureEtReponsesRapides_donne50() {
        List<ProctorEvent> e = new ArrayList<>();
        e.add(ev(EventType.APP_BACKGROUND, 2L, null, null));
        e.add(ev(EventType.APP_BACKGROUND, 5L, null, null));
        e.add(ev(EventType.SCREENSHOT, 8L, null, null));
        String[] choix = {"A", "C", "B", "D", "B", "A", "C", "B", "D", "A"};
        for (int i = 0; i < 10; i++) e.add(ev(EventType.ANSWER, (long) i + 1, choix[i], i < 6 ? 1_000L : 20_000L));
        RiskReport r = calcul(e, session("TN", "TN"));
        assertEquals(50, r.scoreRisque()); // 10 (sorties) + 20 (capture) + 20 (réponses rapides)
        assertEquals("MOYEN", r.niveau());
    }

    @Test
    void troisSortiesOuPlus_donne25() {
        List<ProctorEvent> e = new ArrayList<>();
        for (int i = 0; i < 4; i++) e.add(ev(EventType.APP_BACKGROUND, null, null, null));
        assertEquals(25, calcul(e, session("TN", "TN")).scoreRisque());
    }

    @Test
    void capturesPlafonneesADeux() {
        List<ProctorEvent> e = new ArrayList<>();
        for (int i = 0; i < 5; i++) e.add(ev(EventType.SCREENSHOT, null, null, null));
        assertEquals(40, calcul(e, session(null, null)).scoreRisque());
    }

    @Test
    void paysDifferent_ajoute25() {
        RiskReport r = calcul(List.of(), session("TN", "FR"));
        assertEquals(25, r.scoreRisque());
        assertEquals("PAYS_DIFFERENT", r.alertes().get(0).regle());
    }

    @Test
    void memeLettrePartout_donneMotifSuspect() {
        List<ProctorEvent> e = new ArrayList<>();
        for (int i = 0; i < 8; i++) e.add(ev(EventType.ANSWER, (long) i + 1, "C", 12_000L));
        RiskReport r = calcul(e, session("TN", "TN"));
        assertEquals(15, r.scoreRisque());
        assertEquals("FAIBLE", r.niveau()); // 15 points : à surveiller mais pas alarmant
        assertEquals("MOTIF_SUSPECT", r.alertes().get(0).regle());
    }

    @Test
    void sequenceABCDRepetee_donneMotifSuspect() {
        List<ProctorEvent> e = new ArrayList<>();
        String[] cycle = {"A", "B", "C", "D"};
        for (int i = 0; i < 8; i++) e.add(ev(EventType.ANSWER, (long) i + 1, cycle[i % 4], 12_000L));
        assertEquals(15, calcul(e, session("TN", "TN")).scoreRisque());
    }

    @Test
    void changementDeReponseRemplaceLaReponseFinale() {
        List<ProctorEvent> e = new ArrayList<>();
        // 6 premières réponses "A", mais le candidat en change 3 : plus de 80 % de A n'est plus vrai
        for (int i = 0; i < 6; i++) e.add(ev(EventType.ANSWER, (long) i + 1, "A", 12_000L));
        for (int i = 0; i < 3; i++) e.add(ev(EventType.ANSWER_CHANGED, (long) i + 1, "B", null));
        assertEquals(0, calcul(e, session("TN", "TN")).scoreRisque());
    }

    @Test
    void tempsAnormalParRapportAuxAutres() {
        // 10 autres candidats : 20 s ± quelques secondes ; ce candidat répond en 1 s à la question 1
        List<Long> autres = List.of(18_000L, 20_000L, 22_000L, 19_000L, 21_000L, 20_500L, 19_500L, 21_500L, 18_500L, 20_000L);
        List<ProctorEvent> e = List.of(ev(EventType.ANSWER, 1L, "B", 1_000L));
        RiskReport r = scoring.calculer(session("TN", "TN"), e, q -> autres);
        assertEquals(10, r.scoreRisque());
        assertEquals("TEMPS_ANORMAL", r.alertes().get(0).regle());
    }

    @Test
    void scorePlafonneA100_etNiveauEleve() {
        List<ProctorEvent> e = new ArrayList<>();
        for (int i = 0; i < 5; i++) e.add(ev(EventType.APP_BACKGROUND, null, null, null));
        for (int i = 0; i < 3; i++) e.add(ev(EventType.SCREENSHOT, null, null, null));
        for (int i = 0; i < 8; i++) e.add(ev(EventType.ANSWER, (long) i + 1, "A", 500L));
        RiskReport r = calcul(e, session("TN", "FR"));
        assertEquals(100, r.scoreRisque());
        assertEquals("ELEVE", r.niveau());
    }
}
