package com.psytech.proctoring.service;

import com.psytech.proctoring.model.EventType;
import com.psytech.proctoring.model.ProctorEvent;
import com.psytech.proctoring.model.ProctoringSession;
import com.psytech.proctoring.repository.EventRepository;
import com.psytech.proctoring.web.dto.Alerte;
import com.psytech.proctoring.web.dto.RiskReport;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

/**
 * Moteur de score de risque (0-100) : règles pondérées + comparaison statistique avec les autres candidats.
 * Le score SIGNALE, il ne prouve rien : la décision reste humaine (RH).
 */
@Service
public class ScoringService {

    // Seuils et poids : valeurs de départ, à ajuster avec quelques tests réels.
    static final long SEUIL_REPONSE_RAPIDE_MS = 3_000;
    static final int POURCENT_RAPIDES_ALERTE = 30;
    static final int MIN_REPONSES_POUR_STATS = 5;
    static final int MIN_AUTRES_POUR_COMPARER = 10;
    static final double ECARTS_TYPES = 2.0;
    static final double PART_MEME_LETTRE = 0.8;
    static final String CYCLE = "ABCD";

    private final EventRepository events;

    public ScoringService(EventRepository events) {
        this.events = events;
    }

    public RiskReport calculer(ProctoringSession s, List<ProctorEvent> evts) {
        return calculer(s, evts,
                questionId -> events.tempsDesAutres(EventType.ANSWER, questionId, s.getInvitationId()));
    }

    /** Version pure (testable) : {@code tempsDesAutres} donne les temps des autres candidats pour une question. */
    RiskReport calculer(ProctoringSession s, List<ProctorEvent> evts, Function<Long, List<Long>> tempsDesAutres) {
        List<Alerte> alertes = new ArrayList<>();

        // 1. Sorties de l'application
        long sorties = compter(evts, EventType.APP_BACKGROUND);
        if (sorties >= 3) alertes.add(new Alerte("SORTIES_APP", sorties + " sorties de l'application", 25));
        else if (sorties >= 1) alertes.add(new Alerte("SORTIES_APP", sorties + " sortie(s) de l'application", 10));

        // 2. Captures d'écran (2 comptées au maximum)
        long captures = compter(evts, EventType.SCREENSHOT);
        if (captures > 0) {
            String ou = evts.stream().filter(e -> e.getType() == EventType.SCREENSHOT && e.getQuestionId() != null)
                    .map(e -> "question " + e.getQuestionId()).distinct().reduce((a, b) -> a + ", " + b)
                    .map(x -> " (" + x + ")").orElse("");
            alertes.add(new Alerte("CAPTURE_ECRAN", captures + " capture(s) d'écran" + ou, (int) Math.min(captures, 2) * 20));
        }

        // Premières réponses (avec leur temps) et réponses finales, dans l'ordre où le candidat a répondu
        Map<Long, Long> premierTemps = new LinkedHashMap<>();
        Map<Long, String> finales = new LinkedHashMap<>();
        for (ProctorEvent e : evts) {
            if (e.getQuestionId() == null || e.getChoix() == null) continue;
            if (e.getType() == EventType.ANSWER) {
                premierTemps.putIfAbsent(e.getQuestionId(), e.getTempsMs());
                finales.put(e.getQuestionId(), e.getChoix());
            } else if (e.getType() == EventType.ANSWER_CHANGED && finales.containsKey(e.getQuestionId())) {
                finales.put(e.getQuestionId(), e.getChoix());
            }
        }

        // 3. Réponses très rapides (seuil fixe)
        List<Long> temps = premierTemps.values().stream().filter(t -> t != null).toList();
        if (temps.size() >= MIN_REPONSES_POUR_STATS) {
            long rapides = temps.stream().filter(t -> t < SEUIL_REPONSE_RAPIDE_MS).count();
            if (rapides * 100 / temps.size() > POURCENT_RAPIDES_ALERTE) {
                alertes.add(new Alerte("REPONSES_RAPIDES",
                        rapides + "/" + temps.size() + " réponses en moins de " + SEUIL_REPONSE_RAPIDE_MS / 1000 + " s", 20));
            }
        }

        // 4. Temps anormal par rapport aux autres candidats (z-score)
        int anomalies = 0;
        for (Map.Entry<Long, Long> e : premierTemps.entrySet()) {
            if (e.getValue() == null) continue;
            List<Long> autres = tempsDesAutres.apply(e.getKey());
            if (autres == null || autres.size() < MIN_AUTRES_POUR_COMPARER) continue;
            double moyenne = autres.stream().mapToLong(Long::longValue).average().orElse(0);
            double variance = autres.stream().mapToDouble(t -> (t - moyenne) * (t - moyenne)).average().orElse(0);
            double ecartType = Math.sqrt(variance);
            if (ecartType > 0 && e.getValue() < moyenne - ECARTS_TYPES * ecartType) anomalies++;
        }
        if (anomalies > 0) {
            alertes.add(new Alerte("TEMPS_ANORMAL",
                    anomalies + " question(s) répondue(s) bien plus vite que les autres candidats", Math.min(anomalies * 10, 20)));
        }

        // 5. Motif de réponses suspect
        List<String> sequence = new ArrayList<>(finales.values());
        String motif = motifSuspect(sequence);
        if (motif != null) alertes.add(new Alerte("MOTIF_SUSPECT", motif, 15));

        // 6. Pays différent entre le début et la fin
        if (s.getPaysDepart() != null && s.getDernierPays() != null && !s.getPaysDepart().equalsIgnoreCase(s.getDernierPays())) {
            alertes.add(new Alerte("PAYS_DIFFERENT",
                    "Pays au départ : " + s.getPaysDepart() + ", pays à la fin : " + s.getDernierPays(), 25));
        }

        int total = Math.min(100, alertes.stream().mapToInt(Alerte::points).sum());
        return new RiskReport(s.getInvitationId(), total, niveau(total), alertes, evts.size(), s.getPaysDepart(), s.getDernierPays());
    }

    static String niveau(int score) {
        return score >= 60 ? "ELEVE" : score >= 30 ? "MOYEN" : "FAIBLE";
    }

    /** Retourne une description si la séquence de réponses ressemble à du hasard « paresseux », sinon null. */
    static String motifSuspect(List<String> seq) {
        if (seq.size() < MIN_REPONSES_POUR_STATS) return null;
        Map<String, Integer> freq = new HashMap<>();
        seq.forEach(c -> freq.merge(c, 1, Integer::sum));
        var max = freq.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow();
        if (max.getValue() > PART_MEME_LETTRE * seq.size()) {
            return "La lettre " + max.getKey() + " est choisie pour " + max.getValue() + "/" + seq.size() + " réponses";
        }
        if (seq.size() >= 6 && (suitCycle(seq, false) || suitCycle(seq, true))) {
            return "Réponses en séquence répétée (A-B-C-D)";
        }
        return null;
    }

    private static boolean suitCycle(List<String> seq, boolean inverse) {
        String base = inverse ? new StringBuilder(CYCLE).reverse().toString() : CYCLE;
        for (int decalage = 0; decalage < 4; decalage++) {
            boolean ok = true;
            for (int i = 0; i < seq.size() && ok; i++) {
                ok = seq.get(i).charAt(0) == base.charAt((i + decalage) % 4);
            }
            if (ok) return true;
        }
        return false;
    }

    private static long compter(List<ProctorEvent> evts, EventType type) {
        return evts.stream().filter(e -> e.getType() == type).count();
    }
}
