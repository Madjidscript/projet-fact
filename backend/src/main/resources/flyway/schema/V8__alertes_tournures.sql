-- =============================================================================
-- Étape 11, retouche : tournures des alertes (« Loyer d'octobre », « lot A1, Immeuble X »)
-- =============================================================================

/** « de mars 2026 », « d'avril 2026 » : l'élision devant une voyelle. */
CREATE FUNCTION app.du_mois(p date) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE WHEN extract(month FROM p)::int IN (4, 8, 10) THEN 'd''' ELSE 'de ' END || app.mois(p)
$$;

CREATE OR REPLACE FUNCTION app.generer_alertes() RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    n      integer := 0;
    k      integer;
    j_bail integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'bail.alerte_echeance_jours'), 60);
    j_rel  integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'bail.relance_echeance_jours'), 30);
    j_vac  integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'lot.vacance_alerte_jours'), 30);
    j_man  integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'mandat.alerte_renouvellement_jours'), 90);
    j_gr   integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'loyer.jours_de_grace'), 5);
    j_rl   integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'loyer.relance_jours'), 15);
    sans   integer;
BEGIN
    -- 1. Bail arrivant à échéance : 60 puis 30 jours avant
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT d, 'BAIL_ECHEANCE', b.id || '-' || p.palier, CASE WHEN p.palier = j_rel THEN 'ATTENTION' ELSE 'INFO' END,
           'Bail ' || b.reference || ' : fin le ' || to_char(b.date_fin, 'DD/MM/YYYY'),
           b.occupant || ', lot ' || b.numero_lot || ', ' || bt.nom
               || '. Renouvelez le bail avec le locataire ou préparez sa sortie.',
           '/patrimoine/' || bt.id || '?lot=' || b.id_lot, bt.id
    FROM loc.v_bail b
    JOIN pat.batiment bt ON bt.id = b.id_batiment AND bt.archive_le IS NULL
    CROSS JOIN LATERAL (SELECT CASE WHEN b.date_fin - current_date <= j_rel THEN j_rel ELSE j_bail END AS palier) p
    CROSS JOIN LATERAL app.destinataires(bt.id, false) d
    WHERE b.statut = 'ACTIF' AND b.date_fin >= current_date AND b.date_fin - current_date <= j_bail
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- 2. Loyer en retard : J+5 puis J+15 après l'exigibilité (échéances des 45 derniers jours)
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT d, 'LOYER_RETARD', e.id || '-' || p.palier, CASE WHEN p.palier = j_rl THEN 'CRITIQUE' ELSE 'ATTENTION' END,
           'Loyer impayé : ' || b.occupant,
           'Loyer ' || app.du_mois(e.periode) || ', lot ' || b.numero_lot || ', ' || bt.nom || ' : '
               || app.montant(e.reste) || ' FCFA restent dus depuis le ' || to_char(e.date_exigibilite, 'DD/MM/YYYY')
               || '.' || coalesce(' Téléphone : ' || b.telephone_occupant || '.', ''),
           '/patrimoine/' || bt.id || '?lot=' || b.id_lot, bt.id
    FROM loc.v_echeance e
    JOIN loc.v_bail b ON b.id = e.id_bail AND b.statut IN ('ACTIF', 'PREAVIS')
    JOIN pat.batiment bt ON bt.id = b.id_batiment AND bt.archive_le IS NULL
    CROSS JOIN LATERAL (SELECT CASE WHEN current_date - e.date_exigibilite >= j_rl THEN j_rl ELSE j_gr END AS palier) p
    CROSS JOIN LATERAL app.destinataires(bt.id, false) d
    WHERE e.reste > 0 AND current_date - e.date_exigibilite >= j_gr AND e.date_exigibilite >= current_date - 45
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- 3. Lot vacant depuis 30 jours : gestionnaire et Direction
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT d, 'LOT_VACANT', l.id || '-' || l.statut_depuis, 'ATTENTION',
           'Lot ' || l.numero || ' vacant depuis ' || (current_date - l.statut_depuis) || ' jours',
           bt.nom || ' : libre depuis le ' || to_char(l.statut_depuis, 'DD/MM/YYYY')
               || coalesce(', loyer de référence ' || app.montant(l.loyer_reference) || ' FCFA', '')
               || '. Publiez une annonce ou revoyez le loyer.',
           '/patrimoine/' || bt.id || '?lot=' || l.id, bt.id
    FROM pat.lot l
    JOIN pat.batiment bt ON bt.id = l.id_batiment AND bt.archive_le IS NULL
    CROSS JOIN LATERAL app.destinataires(bt.id, true) d
    WHERE l.archive_le IS NULL AND l.statut IN ('VACANT', 'A_LOUER') AND current_date - l.statut_depuis >= j_vac
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- 4. Mandat à renouveler : 90 jours avant son échéance, à la Direction
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien)
    SELECT d, 'MANDAT_ECHEANCE', m.id || '-' || m.date_fin, CASE WHEN m.jours_restants <= 30 THEN 'ATTENTION' ELSE 'INFO' END,
           'Mandat ' || m.reference || ' (' || m.proprietaire || ') : échéance le ' || to_char(m.date_fin, 'DD/MM/YYYY'),
           CASE WHEN m.reconduction_tacite THEN 'Il sera reconduit d''office pour ' || m.duree_mois
                     || ' mois s''il n''est pas dénoncé. Faites le point avec le propriétaire.'
                ELSE 'Il n''est pas reconduit d''office : renouvelez-le avec le propriétaire.' END,
           '/proprietaires?fiche=' || m.id_proprietaire
    FROM pat.v_mandat m
    CROSS JOIN app.direction() d
    WHERE m.statut = 'ACTIF' AND m.jours_restants BETWEEN 0 AND j_man
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- 5. Intervention urgente : dès le signalement, au responsable travaux
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT d, 'INTERVENTION_URGENTE', i.id::text, CASE WHEN i.urgence = 'CRITIQUE' THEN 'CRITIQUE' ELSE 'ATTENTION' END,
           'Intervention urgente : ' || i.titre,
           i.reference || ', ' || bt.nom || coalesce(', lot ' || l.numero, '') || '. Affectez un prestataire au plus vite.',
           '/travaux?intervention=' || i.id, bt.id
    FROM tra.intervention i
    JOIN pat.batiment bt ON bt.id = i.id_batiment
    LEFT JOIN pat.lot l ON l.id = i.id_lot
    CROSS JOIN app.responsables_travaux() d
    WHERE i.urgence IN ('CRITIQUE', 'HAUTE') AND i.statut IN ('SIGNALEE', 'AFFECTEE', 'EN_COURS', 'VALIDEE')
      AND i.signale_le >= now() - interval '30 days'
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- 6. Bâtiments sans gestionnaire : un point par jour à la Direction (celui de la veille s'efface)
    SELECT count(*) INTO sans FROM pat.batiment WHERE archive_le IS NULL AND id_gestionnaire IS NULL;
    UPDATE app.alerte SET resolue_le = now()
    WHERE type = 'SANS_GESTIONNAIRE' AND resolue_le IS NULL AND (cle <> current_date::text OR sans = 0);
    IF sans > 0 THEN
        INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien)
        SELECT d, 'SANS_GESTIONNAIRE', current_date::text, 'INFO',
               sans || CASE WHEN sans > 1 THEN ' bâtiments n''ont' ELSE ' bâtiment n''a' END || ' pas de gestionnaire',
               'Confiez-les à un gestionnaire pour que leurs loyers et leurs alertes soient suivis.',
               '/patrimoine?filtre=sans-gestionnaire'
        FROM app.direction() d
        ON CONFLICT DO NOTHING;
        GET DIAGNOSTICS k = ROW_COUNT; n := n + k;
    END IF;

    -- Les situations rentrées dans l'ordre
    UPDATE app.alerte a SET resolue_le = now()
    WHERE a.resolue_le IS NULL AND a.type = 'LOYER_RETARD'
      AND NOT EXISTS (SELECT 1 FROM loc.v_echeance e WHERE e.id = split_part(a.cle, '-', 1)::bigint AND e.reste > 0);
    UPDATE app.alerte a SET resolue_le = now()
    WHERE a.resolue_le IS NULL AND a.type = 'LOT_VACANT'
      AND NOT EXISTS (SELECT 1 FROM pat.lot l WHERE l.id = split_part(a.cle, '-', 1)::int
                      AND l.statut IN ('VACANT', 'A_LOUER') AND l.statut_depuis::text = substr(a.cle, strpos(a.cle, '-') + 1));
    UPDATE app.alerte a SET resolue_le = now()
    WHERE a.resolue_le IS NULL AND a.type = 'INTERVENTION_URGENTE'
      AND NOT EXISTS (SELECT 1 FROM tra.intervention i WHERE i.id = a.cle::int
                      AND i.statut IN ('SIGNALEE', 'AFFECTEE', 'EN_COURS', 'VALIDEE'));
    UPDATE app.alerte a SET resolue_le = now()
    WHERE a.resolue_le IS NULL AND a.type = 'BAIL_ECHEANCE'
      AND NOT EXISTS (SELECT 1 FROM loc.v_bail b WHERE b.id = split_part(a.cle, '-', 1)::int
                      AND b.statut = 'ACTIF' AND b.date_fin >= current_date);
    UPDATE app.alerte a SET resolue_le = now()
    WHERE a.resolue_le IS NULL AND a.type = 'MANDAT_ECHEANCE'
      AND NOT EXISTS (SELECT 1 FROM pat.v_mandat m WHERE m.id = split_part(a.cle, '-', 1)::int
                      AND m.statut = 'ACTIF' AND m.date_fin::text = substr(a.cle, strpos(a.cle, '-') + 1));
    RETURN n;
END;
$$;

-- Les alertes déjà créées sont refaites avec les nouvelles tournures au prochain passage.
DELETE FROM app.alerte;
