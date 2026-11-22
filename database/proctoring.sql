-- =====================================================================
--  PsyTech Pro — Surveillance (proctoring-service)
--  À importer UNE FOIS dans phpMyAdmin (base psytech_pro), après schema.sql.
--  Copie locale du dernier rapport de risque calculé par le service Spring :
--  elle reste consultable même si le service Spring est arrêté.
-- =====================================================================
USE psytech_pro;

CREATE TABLE IF NOT EXISTS rapports_risque (
  invitation_id INT NOT NULL PRIMARY KEY,
  score_risque INT NOT NULL,
  niveau ENUM('FAIBLE','MOYEN','ELEVE') NOT NULL,
  alertes JSON NOT NULL,                                -- [{regle, detail, points}, ...]
  nb_evenements INT NOT NULL DEFAULT 0,
  pays_depart VARCHAR(8) NULL,
  pays_fin VARCHAR(8) NULL,
  updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  CHECK (score_risque BETWEEN 0 AND 100),
  FOREIGN KEY (invitation_id) REFERENCES invitations(id) ON DELETE CASCADE
);
