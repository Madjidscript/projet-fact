-- =============================================================================
-- V13 : la vue des interventions reprend la réponse du propriétaire (V12)
-- -----------------------------------------------------------------------------
-- Une vue PostgreSQL fige la liste de ses colonnes à sa création : « i.* » ne
-- comprend pas les colonnes accord_* ajoutées à tra.intervention par V12.
-- La vue est recréée à l'identique ; aucune autre vue n'en dépend.
-- =============================================================================

DROP VIEW tra.v_intervention;

CREATE VIEW tra.v_intervention AS
SELECT i.*, b.nom AS batiment, b.reference AS reference_batiment, b.id_gestionnaire, g.nom AS gestionnaire,
       c.libelle AS commune, q.libelle AS quartier, ST_Y(b.geom) AS latitude, ST_X(b.geom) AS longitude,
       l.numero AS numero_lot, p.nom AS prestataire, p.telephone AS telephone_prestataire,
       (SELECT count(*) FROM tra.photo ph WHERE ph.id_intervention = i.id) AS photos,
       CASE WHEN i.cloture_le IS NOT NULL THEN round(extract(epoch FROM i.cloture_le - i.signale_le) / 86400.0, 1) END AS delai_jours,
       i.statut NOT IN ('CLOTUREE', 'ANNULEE') AS ouverte
FROM tra.intervention i
JOIN pat.batiment b ON b.id = i.id_batiment
JOIN adm.commune c ON c.id_commune = b.id_commune
LEFT JOIN adm.quartier q ON q.id_quartier = b.id_quartier
LEFT JOIN app.compte g ON g.id = b.id_gestionnaire
LEFT JOIN pat.lot l ON l.id = i.id_lot
LEFT JOIN tra.prestataire p ON p.id = i.id_prestataire;

COMMENT ON VIEW tra.v_intervention IS 'Synthèse d''une intervention : bâtiment, lot, prestataire, délais, réponse du propriétaire';
