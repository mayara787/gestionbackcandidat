const router = require('express').Router();
const { z } = require('zod');
const db = require('../../config/db');
const { requireCandidat, chargerInvitation } = require('../../middlewares/auth');
const validate = require('../../middlewares/validate');
const AppError = require('../../utils/AppError');
const { logAction } = require('../../utils/audit');
const { calculerScore } = require('../../utils/score');
const { sendMail } = require('../../utils/mailer');
const proctoring = require('../../utils/proctoring');

router.use(requireCandidat);

const statutEffectif = (inv) => (inv.statut === 'invite' && inv.expiree ? 'expire' : inv.statut);

/** Corrige et clôt le test (idempotent, transactionnel). Envoie l'email de confirmation. */
async function finaliser(req, inv) {
  const conn = await db.getConnection();
  let fait = false;
  try {
    await conn.beginTransaction();
    const [[cur]] = await conn.query('SELECT statut FROM invitations WHERE id = ? FOR UPDATE', [inv.id]);
    if (cur.statut !== 'termine') {
      const { score, scoreMax } = await calculerScore(conn, inv.id, inv.test_id);
      await conn.execute(
        `INSERT INTO resultats (invitation_id, score, score_max, reussi, duree_secondes)
         SELECT i.id, ?, ?, ?, LEAST(TIMESTAMPDIFF(SECOND, i.started_at, NOW()), t.duree_minutes * 60)
         FROM invitations i JOIN tests t ON t.id = i.test_id WHERE i.id = ?`,
        [score, scoreMax, score >= inv.score_minimum ? 1 : 0, inv.id]
      );
      await conn.execute(`UPDATE invitations SET statut = 'termine', termine_at = NOW() WHERE id = ?`, [inv.id]);
      fait = true;
    }
    await conn.commit();
  } catch (e) {
    await conn.rollback();
    throw e;
  } finally {
    conn.release();
  }
  if (fait) {
    await logAction(req, { action: 'TEST_SOUMIS', entite: 'invitation', entiteId: inv.id });
    // Surveillance: récupère en arrière-plan le rapport de risque du service Spring (sans bloquer le candidat)
    proctoring.collecterPlusTard(inv.id);
    await sendMail({
      to: inv.email,
      subject: `Confirmation: test « ${inv.titre} » soumis`,
      text: `Bonjour ${inv.nom},\n\nNous confirmons la réception de vos réponses au test « ${inv.titre} ». ${inv.entreprise_nom} reviendra vers vous.\n\nMerci et bonne continuation.`,
    });
  }
  return fait;
}

/** Vérifie que le test est en cours et que le temps n'est pas écoulé (sinon soumission automatique). */
async function garantirEnCours(req) {
  const inv = req.inv;
  if (inv.statut === 'termine') throw new AppError(409, 'Ce test est déjà terminé.');
  if (statutEffectif(inv) === 'expire') throw new AppError(410, 'Cette invitation a expiré.');
  if (inv.statut !== 'en_cours') throw new AppError(409, 'Le test n’a pas encore été démarré.');
  if (Number(inv.reste_secondes) <= 0) {
    await finaliser(req, inv);
    throw new AppError(409, 'Temps écoulé: votre test a été soumis automatiquement.');
  }
  return inv;
}

async function contenu(inv) {
  const [qs] = await db.query(
    `SELECT q.id, tq.position, q.enonce, q.option_a, q.option_b, q.option_c, q.option_d, q.points
     FROM test_questions tq JOIN questions q ON q.id = tq.question_id
     WHERE tq.test_id = ? ORDER BY tq.position`,
    [inv.test_id]
  );
  const [rs] = await db.query('SELECT question_id, choix FROM reponses WHERE invitation_id = ?', [inv.id]);
  return {
    titre: inv.titre,
    dureeMinutes: inv.duree_minutes,
    resteSecondes: Number(inv.reste_secondes),
    total: qs.length,
    repondues: rs.length,
    // Jamais de bonne réponse envoyée au candidat
    questions: qs.map((q) => ({
      id: q.id, position: q.position, enonce: q.enonce, points: q.points,
      options: { A: q.option_a, B: q.option_b, C: q.option_c, D: q.option_d },
    })),
    reponses: Object.fromEntries(rs.map((r) => [r.question_id, r.choix])),
  };
}

async function resultat(id) {
  const inv = await chargerInvitation(id);
  const [[r]] = await db.execute('SELECT score, score_max FROM resultats WHERE invitation_id = ?', [id]);
  const visible = Boolean(inv.afficher_score);
  return { termine: true, titre: inv.titre, scoreVisible: visible, ...(visible ? { score: r.score, scoreMax: r.score_max } : {}) };
}

// GET /api/candidat/test   -> infos du test (titre, durée, nb questions)
router.get('/', async (req, res) => {
  const inv = req.inv;
  const [[c]] = await db.execute('SELECT COUNT(*) AS n FROM test_questions WHERE test_id = ?', [inv.test_id]);
  res.json({
    titre: inv.titre,
    description: inv.description,
    dureeMinutes: inv.duree_minutes,
    nbQuestions: Number(c.n),
    statut: statutEffectif(inv),
    resteSecondes: inv.statut === 'en_cours' ? Number(inv.reste_secondes) : null,
    candidat: inv.nom,
  });
});

// POST /api/candidat/test/demarrer   -> lance le chrono (une seule fois) et renvoie les questions
router.post('/demarrer', async (req, res) => {
  let inv = req.inv;
  if (inv.statut === 'termine') throw new AppError(409, 'Ce test est déjà terminé.');
  if (statutEffectif(inv) === 'expire') throw new AppError(410, 'Cette invitation a expiré.');
  if (inv.test_statut !== 'publie') throw new AppError(409, 'Ce test n’est pas disponible pour le moment.');
  if (inv.statut === 'invite') {
    await db.execute(`UPDATE invitations SET statut = 'en_cours', started_at = NOW() WHERE id = ? AND statut = 'invite'`, [inv.id]);
    await logAction(req, { action: 'TEST_DEMARRE', entite: 'invitation', entiteId: inv.id });
    inv = await chargerInvitation(inv.id);
    req.inv = inv;
  }
  await garantirEnCours(req);
  res.json(await contenu(req.inv));
});

// GET /api/candidat/test/questions   -> reprise après rafraîchissement de la page
router.get('/questions', async (req, res) => {
  await garantirEnCours(req);
  res.json(await contenu(req.inv));
});

// PUT /api/candidat/test/reponses/:questionId   { choix: "A" }   (modifiable tant que non soumis)
router.put('/reponses/:questionId', validate(z.object({ choix: z.enum(['A', 'B', 'C', 'D'], { message: 'Choix: A, B, C ou D' }) })), async (req, res) => {
  const inv = await garantirEnCours(req);
  const qid = Number(req.params.questionId);
  if (!Number.isInteger(qid) || qid < 1) throw new AppError(400, 'Question invalide.');
  const [ok] = await db.execute('SELECT 1 FROM test_questions WHERE test_id = ? AND question_id = ?', [inv.test_id, qid]);
  if (!ok[0]) throw new AppError(404, 'Question introuvable dans ce test.');
  await db.execute(
    'INSERT INTO reponses (invitation_id, question_id, choix) VALUES (?,?,?) ON DUPLICATE KEY UPDATE choix = VALUES(choix)',
    [inv.id, qid, req.valid.choix]
  );
  const [[c]] = await db.execute('SELECT COUNT(*) AS n FROM reponses WHERE invitation_id = ?', [inv.id]);
  res.json({ questionId: qid, choix: req.valid.choix, repondues: Number(c.n), resteSecondes: Number(inv.reste_secondes) });
});

// POST /api/candidat/test/soumettre   -> corrige, enregistre le résultat, email de confirmation
router.post('/soumettre', async (req, res) => {
  const inv = req.inv;
  if (statutEffectif(inv) === 'expire') throw new AppError(410, 'Cette invitation a expiré.');
  if (inv.statut === 'invite') throw new AppError(409, 'Le test n’a pas encore été démarré.');
  if (inv.statut === 'en_cours') await finaliser(req, inv);
  res.json(await resultat(inv.id));
});

// GET /api/candidat/test/resultat   -> score (seulement si l'entreprise l'autorise)
router.get('/resultat', async (req, res) => {
  if (req.inv.statut !== 'termine') throw new AppError(409, 'Le test n’est pas encore terminé.');
  res.json(await resultat(req.inv.id));
});

module.exports = router;
