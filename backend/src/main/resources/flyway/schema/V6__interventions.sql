-- =============================================================================
-- FACT Immobilier — Interventions et travaux
-- Étape 10 du plan de réalisation (SFD-FACT-2026-01, § 4.5, SIG-13).
-- -----------------------------------------------------------------------------
-- Une intervention est signalée sur un bâtiment, ou sur l'un de ses lots, puis suit
-- un cycle : Signalée → Affectée (prestataire, date prévue) → En cours (constat,
-- devis) → Validée (accord sur le devis) → Terminée (coût réel, photos après) →
-- Clôturée (contrôle, imputation au propriétaire, au locataire ou à l'agence).
-- Une intervention peut être annulée tant qu'elle n'est pas terminée. Rien ne se
-- supprime ; l'historique est dans app.journal (objet INTERVENTION).
-- =============================================================================

CREATE SEQUENCE tra.seq_intervention;

INSERT INTO ref.valeur (domaine, code, libelle, ordre) VALUES
    ('IMPUTATION', 'PROPRIETAIRE', 'À la charge du propriétaire', 1),
    ('IMPUTATION', 'LOCATAIRE',    'À la charge du locataire', 2),
    ('IMPUTATION', 'AGENCE',       'Pris en charge par l''agence', 3),
    ('MOMENT_PHOTO', 'AVANT', 'Avant les travaux', 1),
    ('MOMENT_PHOTO', 'APRES', 'Après les travaux', 2);

INSERT INTO ref.reglage (cle, valeur, libelle, unite) VALUES
    ('travaux.seuil_accord_proprietaire', '150000',
     'Montant de devis au-delà duquel l''accord du propriétaire est requis', 'FCFA');

-- -----------------------------------------------------------------------------
-- Prestataires : techniciens et entreprises à qui l'agence confie les travaux
-- -----------------------------------------------------------------------------
CREATE TABLE tra.prestataire (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    nom         varchar(160) NOT NULL,
    specialite  varchar(40),                    -- CATEGORIE_INTERVENTION
    telephone   varchar(30),
    email       varchar(160),
    notes       text,
    actif       boolean      NOT NULL DEFAULT true,
    cree_le     timestamptz  NOT NULL DEFAULT now(),
    cree_par    varchar(80),
    CONSTRAINT chk_prestataire_nom CHECK (btrim(nom) <> ''),
    CONSTRAINT chk_prestataire_telephone CHECK (telephone IS NULL OR telephone ~ '^\+?[0-9 ]{8,19}$')
);
COMMENT ON TABLE tra.prestataire IS 'Techniciens et entreprises à qui l''agence confie les interventions';

-- -----------------------------------------------------------------------------
-- Interventions
-- -----------------------------------------------------------------------------
CREATE TABLE tra.intervention (
    id                  integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference           varchar(20)  NOT NULL UNIQUE
                        DEFAULT 'INT-' || lpad(nextval('tra.seq_intervention')::text, 4, '0'),
    id_batiment         integer      NOT NULL REFERENCES pat.batiment,
    id_lot              integer      REFERENCES pat.lot,
    categorie           varchar(40)  NOT NULL,      -- CATEGORIE_INTERVENTION
    urgence             varchar(20)  NOT NULL DEFAULT 'NORMALE',   -- URGENCE
    titre               varchar(160) NOT NULL,
    description         text,
    statut              varchar(20)  NOT NULL DEFAULT 'SIGNALEE',
    signale_le          timestamptz  NOT NULL DEFAULT now(),
    signale_par         varchar(80),
    id_prestataire      integer      REFERENCES tra.prestataire,
    date_prevue         date,
    affecte_le          timestamptz,
    affecte_par         varchar(80),
    constat             text,
    devis_montant       integer,
    debute_le           timestamptz,
    accord_proprietaire boolean      NOT NULL DEFAULT false,
    note_validation     varchar(250),
    valide_le           timestamptz,
    valide_par          varchar(80),
    cout_reel           integer,
    note_fin            varchar(250),
    termine_le          timestamptz,
    termine_par         varchar(80),
    imputation          varchar(20),                -- IMPUTATION
    note_cloture        varchar(250),
    cloture_le          timestamptz,
    cloture_par         varchar(80),
    motif_annulation    varchar(250),
    annule_le           timestamptz,
    annule_par          varchar(80),
    maj_le              timestamptz,
    CONSTRAINT chk_intervention_titre CHECK (btrim(titre) <> ''),
    CONSTRAINT chk_intervention_statut CHECK (statut IN ('SIGNALEE', 'AFFECTEE', 'EN_COURS', 'VALIDEE', 'TERMINEE', 'CLOTUREE', 'ANNULEE')),
    CONSTRAINT chk_intervention_montants CHECK ((devis_montant IS NULL OR devis_montant >= 0) AND (cout_reel IS NULL OR cout_reel >= 0)),
    CONSTRAINT chk_intervention_imputation CHECK (statut <> 'CLOTUREE' OR imputation IS NOT NULL),
    CONSTRAINT chk_intervention_annulation CHECK (statut <> 'ANNULEE' OR motif_annulation IS NOT NULL)
);
CREATE INDEX idx_intervention_batiment ON tra.intervention (id_batiment);
CREATE INDEX idx_intervention_ouverte ON tra.intervention (statut) WHERE statut NOT IN ('CLOTUREE', 'ANNULEE');
COMMENT ON TABLE tra.intervention IS 'Pannes et travaux signalés sur un bâtiment ou un lot, et leur cycle de traitement (§ 4.5)';

-- Un lot désigné appartient forcément au bâtiment de l'intervention.
CREATE FUNCTION tra.controler_lot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id_lot IS NOT NULL AND NOT EXISTS (SELECT 1 FROM pat.lot WHERE id = NEW.id_lot AND id_batiment = NEW.id_batiment) THEN
        RAISE EXCEPTION 'Ce lot n''appartient pas au bâtiment de l''intervention.';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER trg_intervention_lot BEFORE INSERT OR UPDATE OF id_lot, id_batiment ON tra.intervention
    FOR EACH ROW EXECUTE FUNCTION tra.controler_lot();

-- -----------------------------------------------------------------------------
-- Photos avant et après les travaux
-- -----------------------------------------------------------------------------
-- Le fichier est rangé hors de la base (file.upload.base-dir) ; la base garde son chemin.
CREATE TABLE tra.photo (
    id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_intervention integer      NOT NULL REFERENCES tra.intervention,
    moment          varchar(10)  NOT NULL,          -- AVANT, APRES
    chemin          varchar(250) NOT NULL UNIQUE,
    nom_origine     varchar(200),
    largeur         integer,
    hauteur         integer,
    taille          integer,
    cree_le         timestamptz  NOT NULL DEFAULT now(),
    cree_par        varchar(80),
    CONSTRAINT chk_photo_moment CHECK (moment IN ('AVANT', 'APRES'))
);
CREATE INDEX idx_photo_intervention ON tra.photo (id_intervention);

-- -----------------------------------------------------------------------------
-- Vue de synthèse d'une intervention : bâtiment, lot, prestataire, délais
-- -----------------------------------------------------------------------------
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

-- -----------------------------------------------------------------------------
-- Le bâtiment porte ses interventions ouvertes (thématique « Travaux », § 4.1)
-- -----------------------------------------------------------------------------
CREATE OR REPLACE VIEW pat.v_batiment AS
SELECT b.id, b.reference, b.nom, b.type, b.adresse, b.id_commune, c.libelle AS commune,
       b.id_quartier, q.libelle AS quartier,
       ST_Y(b.geom) AS latitude, ST_X(b.geom) AS longitude, b.precision_gps,
       b.niveaux, b.annee_construction, b.description,
       b.id_proprietaire, p.reference AS reference_proprietaire,
       btrim(p.nom || ' ' || coalesce(p.prenoms, '')) AS proprietaire, p.id_compte AS id_compte_proprietaire,
       b.id_gestionnaire, g.nom AS gestionnaire, b.id_suppleant, s.nom AS suppleant,
       b.validation, b.origine, b.archive_le, b.cree_le, b.cree_par, b.maj_le, b.maj_par,
       coalesce(l.lots, 0)      AS lots,
       coalesce(l.occupes, 0)   AS lots_occupes,
       coalesce(l.vacants, 0)   AS lots_vacants,
       coalesce(l.loyers, 0)    AS loyers_reference,
       coalesce(x.baux, 0)::int         AS baux_actifs,
       coalesce(x.impaye, 0)::bigint    AS impaye,
       coalesce(x.mois_retard, 0)::int  AS mois_retard_max,
       coalesce(x.loyers, 0)::bigint    AS loyers_baux,
       coalesce(t.ouvertes, 0)::int     AS interventions_ouvertes,
       coalesce(t.urgentes, 0)::int     AS interventions_urgentes
FROM pat.batiment b
JOIN adm.commune c ON c.id_commune = b.id_commune
LEFT JOIN adm.quartier q ON q.id_quartier = b.id_quartier
JOIN pat.proprietaire p ON p.id = b.id_proprietaire
LEFT JOIN app.compte g ON g.id = b.id_gestionnaire
LEFT JOIN app.compte s ON s.id = b.id_suppleant
LEFT JOIN LATERAL (
    SELECT count(*) FILTER (WHERE statut <> 'HORS_GESTION')                    AS lots,
           count(*) FILTER (WHERE statut IN ('OCCUPE', 'EN_PREAVIS'))           AS occupes,
           count(*) FILTER (WHERE statut IN ('VACANT', 'A_LOUER', 'A_VENDRE'))  AS vacants,
           sum(loyer_reference) FILTER (WHERE statut <> 'HORS_GESTION')         AS loyers
    FROM pat.lot WHERE id_batiment = b.id AND archive_le IS NULL
) l ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS baux, sum(vb.impaye) AS impaye, max(vb.mois_retard) AS mois_retard, sum(vb.loyer + vb.charges) AS loyers
    FROM loc.v_bail vb WHERE vb.id_batiment = b.id AND vb.statut IN ('ACTIF', 'PREAVIS')
) x ON true
LEFT JOIN LATERAL (
    SELECT count(*) AS ouvertes, count(*) FILTER (WHERE urgence IN ('HAUTE', 'CRITIQUE')) AS urgentes
    FROM tra.intervention i WHERE i.id_batiment = b.id AND i.statut NOT IN ('CLOTUREE', 'ANNULEE')
) t ON true;
