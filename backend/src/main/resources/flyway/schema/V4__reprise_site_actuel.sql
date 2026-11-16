-- =============================================================================
-- FACT Immobilier — Reprise des trois annonces du site actuel
-- Source : factimmobilier-ci.com, relevé du 12 novembre 2026 (SFD § 7.4).
-- -----------------------------------------------------------------------------
-- Seules les informations relevées sont reprises : titre, loyer, quartier, type.
-- Le site ne donne ni le propriétaire, ni la position, ni la surface, ni le
-- nombre de pièces vérifié : les trois bâtiments entrent donc « à valider » et
-- « à localiser », rattachés à un propriétaire d'attente que l'agence remplacera.
-- =============================================================================

INSERT INTO pat.proprietaire (reference, nom, notes, origine, cree_par)
VALUES ('PRO-SITE', 'Propriétaire à préciser',
        'Propriétaire d''attente des biens repris du site actuel. À remplacer par le vrai mandant de chaque bien.',
        'SITE', 'Reprise du site');

WITH attente AS (SELECT id FROM pat.proprietaire WHERE reference = 'PRO-SITE'),
cocody AS (SELECT id_commune FROM adm.commune WHERE code = 'COCODY'),
b AS (
    INSERT INTO pat.batiment (nom, type, id_commune, id_quartier, description, id_proprietaire, validation, origine, cree_par)
    SELECT v.nom, v.type, cocody.id_commune, q.id_quartier, v.description, attente.id, 'A_VALIDER', 'SITE', 'Reprise du site'
    FROM (VALUES
        ('Villa de M''Badon', 'VILLA', 'COC_MBADON',
         'Annonce reprise du site : « Magnifique villa à M''Badon ». Vérifier le nombre de pièces ; compléter la surface et la position GPS.'),
        ('Appartement F3 de la Cité Sir', 'IMMEUBLE', 'COC_CITE_SIR',
         'Annonce reprise du site : « À louer bel appartement F3 neuf, Cité Sir ». Le site affichait une salle de bain à « -2 » : valeur à corriger.'),
        ('Résidence Signadjégou', 'RESIDENCE', 'COC_ANGRE_9E',
         'Annonce reprise du site : « Résidence Signadjégou, Angré 9e tranche ». Composition du F3 et équipements à reprendre de l''annonce.')
    ) AS v(nom, type, quartier, description)
    CROSS JOIN attente CROSS JOIN cocody
    JOIN adm.quartier q ON q.code = v.quartier
    RETURNING id, nom
)
INSERT INTO pat.lot (id_batiment, numero, etage, type, loyer_reference, statut, description, cree_par)
SELECT b.id, v.numero, 0, v.type, v.loyer, 'A_LOUER', v.description, 'Reprise du site'
FROM (VALUES
    ('Villa de M''Badon',             'Villa', 'VILLA', 2500000, 'Loyer affiché sur le site : 2 500 000 FCFA.'),
    ('Appartement F3 de la Cité Sir', 'F3',    'F3',     285000, 'Loyer affiché sur le site : 285 000 FCFA.'),
    ('Résidence Signadjégou',         'F3',    'F3',     375000, 'Loyer affiché sur le site : 375 000 FCFA.')
) AS v(batiment, numero, type, loyer, description)
JOIN b ON b.nom = v.batiment;
