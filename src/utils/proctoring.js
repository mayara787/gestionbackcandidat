const db = require('../config/db');

/**
 * Client du service Spring « proctoring-service » (détection d'anomalies).
 * Node lui demande le rapport de risque d'un candidat avec une clé de service (X-Service-Key).
 * Si le service est arrêté ou non configuré, PsyTech continue de fonctionner normalement.
 */
const url = () => (process.env.PROCTORING_URL || '').replace(/\/+$/, '');
const cle = () => process.env.PROCTORING_SERVICE_KEY || '';
const actif = () => Boolean(url() && cle());

/** GET /api/sessions/:id/report  ->  rapport JSON, ou null si le candidat n'a aucune donnée de surveillance. */
async function recupererRapport(invitationId) {
  if (!actif()) return null;
  const r = await fetch(`${url()}/api/sessions/${Number(invitationId)}/report`, {
    headers: { 'X-Service-Key': cle() },
    signal: AbortSignal.timeout(4000),
  });
  if (r.status === 404) return null;
  if (!r.ok) throw new Error(`proctoring-service: HTTP ${r.status}`);
  return r.json();
}

async function stocker(rapport) {
  await db.execute(
    `INSERT INTO rapports_risque (invitation_id, score_risque, niveau, alertes, nb_evenements, pays_depart, pays_fin)
     VALUES (?,?,?,?,?,?,?)
     ON DUPLICATE KEY UPDATE score_risque = VALUES(score_risque), niveau = VALUES(niveau), alertes = VALUES(alertes),
       nb_evenements = VALUES(nb_evenements), pays_depart = VALUES(pays_depart), pays_fin = VALUES(pays_fin)`,
    [
      rapport.invitationId,
      rapport.scoreRisque,
      rapport.niveau,
      JSON.stringify(rapport.alertes || []),
      rapport.nbEvenements || 0,
      rapport.paysDepart || null,
      rapport.paysFin || null,
    ]
  );
}

/** Interroge Spring et enregistre le rapport. Retourne le rapport (ou null). Peut lever une erreur réseau. */
async function collecter(invitationId) {
  const rapport = await recupererRapport(invitationId);
  if (rapport) await stocker(rapport);
  return rapport;
}

/** Après la soumission d'un test : récupère le rapport en arrière-plan (ne ralentit ni ne bloque jamais le candidat). */
function collecterPlusTard(invitationId, delaiMs = 1500) {
  if (!actif()) return;
  const t = setTimeout(() => {
    collecter(invitationId).catch((e) => console.warn(`[proctoring] rapport indisponible (invitation ${invitationId}): ${e.message}`));
  }, delaiMs);
  t.unref();
}

const formater = (row) => ({
  invitationId: row.invitation_id,
  scoreRisque: row.score_risque,
  niveau: row.niveau,
  alertes: typeof row.alertes === 'string' ? JSON.parse(row.alertes) : row.alertes,
  nbEvenements: row.nb_evenements,
  paysDepart: row.pays_depart,
  paysFin: row.pays_fin,
  misAJourLe: row.updated_at,
});

/** Dernier rapport enregistré (copie locale), ou null. */
async function lire(invitationId) {
  const [rows] = await db.execute('SELECT * FROM rapports_risque WHERE invitation_id = ?', [invitationId]);
  return rows[0] ? formater(rows[0]) : null;
}

/** Résumés {scoreRisque, niveau} pour plusieurs candidats (liste des résultats). Map invitationId -> résumé. */
async function lireResumes(ids) {
  const map = new Map();
  if (!ids.length) return map;
  const [rows] = await db.query('SELECT invitation_id, score_risque, niveau FROM rapports_risque WHERE invitation_id IN (?)', [ids]);
  for (const r of rows) map.set(r.invitation_id, { scoreRisque: r.score_risque, niveau: r.niveau });
  return map;
}

module.exports = { actif, collecter, collecterPlusTard, lire, lireResumes };
