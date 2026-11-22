package com.psytech.proctoring.model;

public enum EventType {
    /** Première réponse donnée à une question (porte le temps passé dessus). */
    ANSWER,
    /** Modification d'une réponse déjà donnée. */
    ANSWER_CHANGED,
    /** L'application est passée en arrière-plan (le candidat a quitté l'écran du test). */
    APP_BACKGROUND,
    /** Retour dans l'application. */
    APP_RESUME,
    /** Capture d'écran détectée. */
    SCREENSHOT
}
