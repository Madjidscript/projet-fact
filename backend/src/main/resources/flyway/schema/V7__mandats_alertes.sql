-- =============================================================================
-- Étape 11 : mandats de gestion, alertes automatiques (§ 4.7) et indicateurs (§ 4.8)
-- =============================================================================

-- -----------------------------------------------------------------------------
-- Listes et réglages
-- -----------------------------------------------------------------------------
-- Les types de mandat (TYPE_MANDAT) existent depuis V1.
INSERT INTO ref.reglage (cle, valeur, libelle, unite) VALUES
    ('mandat.taux_honoraires_defaut', '10', 'Taux d''honoraires proposé pour un nouveau mandat', '%'),
    ('loyer.relance_jours',           '15', 'Second rappel d''un loyer impayé, en jours après l''exigibilité', 'jours');

-- -----------------------------------------------------------------------------
-- Mandats (§ 4.2, onglet « Propriétaire et mandat » ; modèle de données : Mandat)
-- -----------------------------------------------------------------------------
CREATE SEQUENCE pat.seq_mandat;

CREATE TABLE pat.mandat (
    id                  integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference           varchar(20)  NOT NULL UNIQUE
                        DEFAULT 'MAN-' || lpad(nextval('pat.seq_mandat')::text, 4, '0'),
    id_proprietaire     integer      NOT NULL REFERENCES pat.proprietaire,
    type                varchar(40)  NOT NULL DEFAULT 'GESTION_LOCATIVE',   -- TYPE_MANDAT
    date_debut          date         NOT NULL,
    duree_mois          smallint     NOT NULL DEFAULT 12,
    reconduction_tacite boolean      NOT NULL DEFAULT true,
    taux_honoraires     numeric(5,2) NOT NULL,                       -- % des loyers encaissés
    seuil_travaux       integer,                                     -- FCFA ; nul : réglage général
    tous_les_biens      boolean      NOT NULL DEFAULT true,          -- faux : seuls les bâtiments listés
    notes               text,
    resilie_le          date,
    motif_resiliation   varchar(250),
    cree_le             timestamptz  NOT NULL DEFAULT now(),
    cree_par            varchar(80),
    maj_le              timestamptz,
    maj_par             varchar(80),
    CONSTRAINT chk_mandat_duree CHECK (duree_mois BETWEEN 1 AND 240),
    CONSTRAINT chk_mandat_taux CHECK (taux_honoraires BETWEEN 0 AND 50),
    CONSTRAINT chk_mandat_seuil CHECK (seuil_travaux IS NULL OR seuil_travaux > 0),
    CONSTRAINT chk_mandat_resiliation CHECK (resilie_le IS NULL OR resilie_le >= date_debut)
);
CREATE INDEX idx_mandat_proprietaire ON pat.mandat (id_proprietaire);
COMMENT ON TABLE pat.mandat IS 'Mandats confiés par les propriétaires : durée, honoraires, seuil de travaux';

CREATE TABLE pat.mandat_batiment (
    id_mandat   integer NOT NULL REFERENCES pat.mandat ON DELETE CASCADE,
    id_batiment integer NOT NULL REFERENCES pat.batiment,
    PRIMARY KEY (id_mandat, id_batiment)
);
COMMENT ON TABLE pat.mandat_batiment IS 'Bâtiments couverts par un mandat qui ne porte pas sur tous les biens du propriétaire';

-- Échéance en cours : avec la reconduction tacite, le mandat repart pour une même durée.
CREATE VIEW pat.v_mandat AS
WITH t AS (
    SELECT m.*,
           (extract(year FROM age(current_date, m.date_debut)) * 12
            + extract(month FROM age(current_date, m.date_debut)))::int AS mois_ecoules
    FROM pat.mandat m
), e AS (
    SELECT t.*,
           (t.date_debut + make_interval(months => t.duree_mois
               * CASE WHEN t.reconduction_tacite AND t.date_debut <= current_date
                      THEN t.mois_ecoules / t.duree_mois + 1 ELSE 1 END) - interval '1 day')::date AS date_fin
    FROM t
)
SELECT e.id, e.reference, e.id_proprietaire, btrim(p.nom || ' ' || coalesce(p.prenoms, '')) AS proprietaire,
       e.type, e.date_debut, e.duree_mois, e.reconduction_tacite, e.taux_honoraires, e.seuil_travaux,
       e.tous_les_biens, e.notes, e.resilie_le, e.motif_resiliation,
       e.date_fin, (e.date_fin - current_date) AS jours_restants,
       CASE WHEN e.resilie_le IS NOT NULL AND e.resilie_le <= current_date THEN 'RESILIE'
            WHEN e.date_debut > current_date THEN 'A_VENIR'
            WHEN e.date_fin < current_date THEN 'ECHU'
            ELSE 'ACTIF' END AS statut,
       e.cree_le, e.cree_par, e.maj_le, e.maj_par
FROM e JOIN pat.proprietaire p ON p.id = e.id_proprietaire;

/** Le mandat en vigueur sur un bâtiment : le plus récent du propriétaire qui le couvre. */
CREATE FUNCTION pat.mandat_actif(p_batiment integer) RETURNS integer LANGUAGE sql STABLE AS $$
    SELECT m.id
    FROM pat.batiment b
    JOIN pat.v_mandat m ON m.id_proprietaire = b.id_proprietaire AND m.statut = 'ACTIF'
    WHERE b.id = p_batiment
      AND (m.tous_les_biens OR EXISTS (SELECT 1 FROM pat.mandat_batiment mb WHERE mb.id_mandat = m.id AND mb.id_batiment = b.id))
    ORDER BY m.date_debut DESC, m.id DESC
    LIMIT 1
$$;

-- -----------------------------------------------------------------------------
-- Alertes (§ 4.7) : une ligne par compte destinataire ; « cle » identifie l'événement
-- pour ne jamais prévenir deux fois de la même chose.
-- -----------------------------------------------------------------------------
CREATE TABLE app.alerte (
    id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_compte   integer      NOT NULL REFERENCES app.compte,
    type        varchar(40)  NOT NULL,
    cle         varchar(80)  NOT NULL,
    gravite     varchar(20)  NOT NULL DEFAULT 'INFO',
    titre       varchar(200) NOT NULL,
    message     text,
    lien        varchar(200),
    id_batiment integer      REFERENCES pat.batiment,
    cree_le     timestamptz  NOT NULL DEFAULT now(),
    lue_le      timestamptz,
    resolue_le  timestamptz,
    CONSTRAINT uq_alerte UNIQUE (id_compte, type, cle),
    CONSTRAINT chk_alerte_type CHECK (type IN ('BAIL_ECHEANCE', 'LOYER_RETARD', 'LOT_VACANT', 'MANDAT_ECHEANCE',
                                                'INTERVENTION_URGENTE', 'SANS_GESTIONNAIRE')),
    CONSTRAINT chk_alerte_gravite CHECK (gravite IN ('INFO', 'ATTENTION', 'CRITIQUE'))
);
CREATE INDEX idx_alerte_compte ON app.alerte (id_compte, cree_le DESC);
COMMENT ON TABLE  app.alerte            IS 'Alertes automatiques adressées aux comptes (§ 4.7)';
COMMENT ON COLUMN app.alerte.resolue_le IS 'La situation est rentrée dans l''ordre (loyer réglé, lot reloué…)';

/** Montant lisible : 1250000 → « 1 250 000 ». */
CREATE FUNCTION app.montant(p bigint) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT regexp_replace(p::text, '(\d)(?=(\d{3})+$)', '\1 ', 'g')
$$;

/** Nom du mois en français, sans dépendre de la langue du serveur. */
CREATE FUNCTION app.mois(p date) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT (ARRAY['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre',
                  'octobre', 'novembre', 'décembre'])[extract(month FROM p)::int] || ' ' || extract(year FROM p)::int
$$;

/** La Direction : les comptes Direction actifs, et les administrateurs. */
CREATE FUNCTION app.direction() RETURNS SETOF integer LANGUAGE sql STABLE AS $$
    SELECT id FROM app.compte WHERE actif AND profil IN ('DIRECTION', 'ADMIN')
$$;

/**
 * Qui prévenir pour un bâtiment : son gestionnaire ; à défaut, la Direction.
 * Avec p_direction, la Direction est prévenue en plus.
 */
CREATE FUNCTION app.destinataires(p_batiment integer, p_direction boolean) RETURNS SETOF integer LANGUAGE sql STABLE AS $$
    SELECT c.id FROM pat.batiment b JOIN app.compte c ON c.id = b.id_gestionnaire AND c.actif WHERE b.id = p_batiment
    UNION
    SELECT d FROM app.direction() d
    WHERE p_direction OR NOT EXISTS (
        SELECT 1 FROM pat.batiment b JOIN app.compte c ON c.id = b.id_gestionnaire AND c.actif WHERE b.id = p_batiment)
$$;

/** Les responsables travaux ; à défaut, la Direction. */
CREATE FUNCTION app.responsables_travaux() RETURNS SETOF integer LANGUAGE sql STABLE AS $$
    SELECT id FROM app.compte WHERE actif AND profil = 'TRAVAUX'
    UNION
    SELECT d FROM app.direction() d WHERE NOT EXISTS (SELECT 1 FROM app.compte WHERE actif AND profil = 'TRAVAUX')
$$;

/**
 * Crée les alertes dues à ce jour et marque résolues celles dont la situation est réglée.
 * Rejouable à volonté : une alerte déjà créée ne l'est pas deux fois. Rend le nombre créé.
 */
CREATE FUNCTION app.generer_alertes() RETURNS integer LANGUAGE plpgsql AS $$
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
           b.occupant || ', lot ' || b.numero_lot || ' de ' || bt.nom
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
           'Loyer de ' || app.mois(e.periode) || ', lot ' || b.numero_lot || ' de ' || bt.nom || ' : '
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
           'Lot ' || l.numero || ' de ' || bt.nom || ' vacant depuis ' || (current_date - l.statut_depuis) || ' jours',
           'Libre depuis le ' || to_char(l.statut_depuis, 'DD/MM/YYYY')
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
           'Mandat ' || m.reference || ' de ' || m.proprietaire || ' : échéance le ' || to_char(m.date_fin, 'DD/MM/YYYY'),
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
COMMENT ON FUNCTION app.generer_alertes() IS 'Alertes du § 4.7 : rejouée toutes les dix minutes et après chaque signalement';
