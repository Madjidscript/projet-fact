-- =============================================================================
-- Étape 12 : espace propriétaire (§ 4.9) — accord en ligne sur les devis, document du
-- mandat, alertes au propriétaire (devis à approuver, relevé disponible).
-- =============================================================================

ALTER TABLE tra.intervention
    ADD COLUMN accord_decision varchar(10),
    ADD COLUMN accord_le       timestamptz,
    ADD COLUMN accord_par      varchar(80),
    ADD COLUMN accord_motif    varchar(250),
    ADD CONSTRAINT chk_intervention_accord CHECK (accord_decision IS NULL OR accord_decision IN ('ACCORD', 'REFUS'));
COMMENT ON COLUMN tra.intervention.accord_decision IS 'Réponse du propriétaire, en ligne, à un devis qui dépasse le seuil de son mandat';

ALTER TABLE pat.mandat
    ADD COLUMN document_chemin varchar(300),
    ADD COLUMN document_nom    varchar(200),
    ADD COLUMN document_le     timestamptz;

ALTER TABLE app.alerte DROP CONSTRAINT chk_alerte_type;
ALTER TABLE app.alerte ADD CONSTRAINT chk_alerte_type CHECK (type IN ('BAIL_ECHEANCE', 'LOYER_RETARD', 'LOT_VACANT',
    'MANDAT_ECHEANCE', 'INTERVENTION_URGENTE', 'SANS_GESTIONNAIRE', 'DEMANDE_NOUVELLE', 'DEMANDE_EN_ATTENTE',
    'ANNONCE_A_VALIDER', 'AFFECTATION', 'DEVIS_A_APPROUVER', 'ACCORD_PROPRIETAIRE', 'RELEVE_DISPONIBLE'));

/** Le seuil d'accord d'un bâtiment : celui de son mandat, à défaut le réglage général. */
CREATE FUNCTION tra.seuil_accord(p_batiment integer) RETURNS integer LANGUAGE sql STABLE AS $$
    SELECT coalesce((SELECT seuil_travaux FROM pat.mandat WHERE id = pat.mandat_actif(p_batiment)),
                    (SELECT valeur::int FROM ref.reglage WHERE cle = 'travaux.seuil_accord_proprietaire'), 150000)
$$;

/**
 * Alertes du propriétaire : un devis attend sa réponse ; son relevé du mois écoulé est disponible
 * (prévenu dans l'application et, s'il a donné son e-mail, par un message dans la boîte d'envoi).
 */
CREATE FUNCTION app.generer_alertes_proprietaires() RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    n    integer := 0;
    k    integer;
    mois date := (date_trunc('month', current_date) - interval '1 month')::date;
BEGIN
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT p.id_compte, 'DEVIS_A_APPROUVER', i.id::text, 'ATTENTION',
           'Devis à approuver : ' || i.titre,
           b.nom || ' : devis de ' || app.montant(i.devis_montant) || ' FCFA, au-delà de votre seuil de '
               || app.montant(tra.seuil_accord(i.id_batiment)) || ' FCFA. Donnez votre accord ou refusez.',
           '/espace?devis=' || i.id, b.id
    FROM tra.intervention i
    JOIN pat.batiment b ON b.id = i.id_batiment
    JOIN pat.proprietaire p ON p.id = b.id_proprietaire AND p.id_compte IS NOT NULL
    JOIN app.compte c ON c.id = p.id_compte AND c.actif
    WHERE i.statut = 'EN_COURS' AND i.devis_montant > tra.seuil_accord(i.id_batiment) AND i.accord_decision IS NULL
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    WITH nouvelles AS (
        INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien)
        SELECT c.id, 'RELEVE_DISPONIBLE', to_char(mois, 'YYYY-MM'), 'INFO',
               'Votre relevé ' || app.du_mois(mois) || ' est disponible',
               'Loyers encaissés, honoraires, travaux et montant reversé : téléchargez-le depuis votre espace.',
               '/espace'
        FROM pat.proprietaire p JOIN app.compte c ON c.id = p.id_compte AND c.actif AND c.profil = 'PROPRIETAIRE'
        WHERE p.archive_le IS NULL AND EXISTS (SELECT 1 FROM pat.batiment b WHERE b.id_proprietaire = p.id AND b.archive_le IS NULL)
        ON CONFLICT DO NOTHING
        RETURNING id_compte)
    INSERT INTO web.message (canal, destinataire, sujet, corps, motif)
    SELECT 'EMAIL', c.email, 'Votre relevé de gestion ' || app.du_mois(mois),
           'Bonjour ' || c.nom || E',\n\nVotre relevé de gestion ' || app.du_mois(mois)
               || E' est disponible dans votre espace propriétaire FACT Immobilier.\n\nCordialement,\nFACT Immobilier',
           'RELEVE'
    FROM nouvelles x JOIN app.compte c ON c.id = x.id_compte WHERE c.email IS NOT NULL;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    UPDATE app.alerte a SET resolue_le = now()
    WHERE a.resolue_le IS NULL AND a.type = 'DEVIS_A_APPROUVER'
      AND NOT EXISTS (SELECT 1 FROM tra.intervention i WHERE i.id = a.cle::int AND i.statut = 'EN_COURS' AND i.accord_decision IS NULL);
    RETURN n;
END;
$$;
