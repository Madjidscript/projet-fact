-- =============================================================================
-- FACT Immobilier — Gestion locative : occupants, baux, échéances, encaissements
-- Étape 9 du plan de réalisation (SFD-FACT-2026-01, § 4.4).
-- -----------------------------------------------------------------------------
-- Chaque mois, chaque bail actif produit une échéance (loyer + charges). Les
-- encaissements d'un bail soldent ses échéances de la plus ancienne à la plus
-- récente : un paiement partiel laisse un reste dû, un trop-perçu devient une
-- avance. Un loyer est en retard quand il n'est pas soldé N jours après son
-- exigibilité (réglage loyer.jours_de_grace). Rien ne se supprime : un
-- encaissement erroné s'annule, avec son motif.
-- =============================================================================

CREATE SEQUENCE loc.seq_occupant;
CREATE SEQUENCE loc.seq_bail;
CREATE SEQUENCE loc.seq_quittance;

-- -----------------------------------------------------------------------------
-- Occupants (locataires)
-- -----------------------------------------------------------------------------
CREATE TABLE loc.occupant (
    id               integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference        varchar(20)  NOT NULL UNIQUE
                     DEFAULT 'LOC-' || lpad(nextval('loc.seq_occupant')::text, 4, '0'),
    type_personne    varchar(40)  NOT NULL DEFAULT 'PHYSIQUE',
    nom              varchar(160) NOT NULL,
    prenoms          varchar(160),
    telephone        varchar(30),
    telephone_2      varchar(30),
    email            varchar(160),
    piece_type       varchar(40),
    piece_numero     varchar(60),
    profession       varchar(120),
    employeur        varchar(160),
    garant_nom       varchar(200),
    garant_telephone varchar(30),
    notes            text,
    archive_le       timestamptz,
    cree_le          timestamptz  NOT NULL DEFAULT now(),
    cree_par         varchar(80),
    maj_le           timestamptz,
    maj_par          varchar(80),
    CONSTRAINT chk_occupant_nom CHECK (btrim(nom) <> ''),
    CONSTRAINT chk_occupant_telephone CHECK (telephone IS NULL OR telephone ~ '^\+?[0-9 ]{8,19}$'),
    CONSTRAINT chk_occupant_telephone_2 CHECK (telephone_2 IS NULL OR telephone_2 ~ '^\+?[0-9 ]{8,19}$'),
    CONSTRAINT chk_occupant_garant_telephone CHECK (garant_telephone IS NULL OR garant_telephone ~ '^\+?[0-9 ]{8,19}$'),
    CONSTRAINT chk_occupant_email CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[a-zA-Z]{2,}$')
);
CREATE INDEX idx_occupant_nom ON loc.occupant (lower(nom));
COMMENT ON TABLE loc.occupant IS 'Locataires : identité, contacts, pièce d''identité, profession, garant (§ 4.4)';

-- -----------------------------------------------------------------------------
-- Baux
-- -----------------------------------------------------------------------------
CREATE TABLE loc.bail (
    id               integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference        varchar(20)  NOT NULL UNIQUE
                     DEFAULT 'BAIL-' || lpad(nextval('loc.seq_bail')::text, 4, '0'),
    id_lot           integer      NOT NULL REFERENCES pat.lot,
    id_occupant      integer      NOT NULL REFERENCES loc.occupant,
    date_effet       date         NOT NULL,
    duree_mois       smallint     NOT NULL DEFAULT 12,
    loyer            integer      NOT NULL,
    charges          integer      NOT NULL DEFAULT 0,
    caution          integer      NOT NULL DEFAULT 0,
    avance_mois      smallint     NOT NULL DEFAULT 0,
    jour_exigibilite smallint     NOT NULL DEFAULT 5,
    statut           varchar(20)  NOT NULL DEFAULT 'ACTIF',
    date_preavis     date,
    date_sortie      date,
    motif_fin        varchar(250),
    notes            text,
    cree_le          timestamptz  NOT NULL DEFAULT now(),
    cree_par         varchar(80),
    maj_le           timestamptz,
    maj_par          varchar(80),
    CONSTRAINT chk_bail_duree CHECK (duree_mois BETWEEN 1 AND 240),
    CONSTRAINT chk_bail_montants CHECK (loyer > 0 AND charges >= 0 AND caution >= 0),
    CONSTRAINT chk_bail_avance CHECK (avance_mois BETWEEN 0 AND 24),
    CONSTRAINT chk_bail_exigibilite CHECK (jour_exigibilite BETWEEN 1 AND 28),
    CONSTRAINT chk_bail_statut CHECK (statut IN ('ACTIF', 'PREAVIS', 'RESILIE', 'ECHU')),
    CONSTRAINT chk_bail_sortie CHECK (date_sortie IS NULL OR date_sortie >= date_effet)
);
-- « Un lot ne peut avoir qu'un seul bail actif à la fois » (§ 4.2)
CREATE UNIQUE INDEX uq_bail_actif ON loc.bail (id_lot) WHERE statut IN ('ACTIF', 'PREAVIS');
CREATE INDEX idx_bail_occupant ON loc.bail (id_occupant);
COMMENT ON TABLE  loc.bail                  IS 'Baux : un lot, un occupant, des dates et des montants (§ 4.4)';
COMMENT ON COLUMN loc.bail.avance_mois      IS 'Nombre de mois de loyer payés d''avance à l''entrée';
COMMENT ON COLUMN loc.bail.jour_exigibilite IS 'Jour du mois où le loyer est dû (1 à 28)';

-- -----------------------------------------------------------------------------
-- Échéances : le loyer attendu, mois par mois
-- -----------------------------------------------------------------------------
CREATE TABLE loc.echeance (
    id               bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_bail          integer     NOT NULL REFERENCES loc.bail,
    periode          date        NOT NULL,      -- premier jour du mois concerné
    date_exigibilite date        NOT NULL,
    loyer            integer     NOT NULL,
    charges          integer     NOT NULL DEFAULT 0,
    montant_du       integer     NOT NULL,
    cree_le          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_echeance UNIQUE (id_bail, periode),
    CONSTRAINT chk_echeance_periode CHECK (extract(day FROM periode) = 1),
    CONSTRAINT chk_echeance_montant CHECK (montant_du >= 0)
);
COMMENT ON TABLE loc.echeance IS 'Loyer attendu de chaque bail, mois par mois (§ 4.4)';

-- -----------------------------------------------------------------------------
-- Encaissements
-- -----------------------------------------------------------------------------
CREATE TABLE loc.encaissement (
    id                 bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    numero             varchar(30)  NOT NULL UNIQUE
                       DEFAULT 'QUI-' || to_char(current_date, 'YYYY') || '-' || lpad(nextval('loc.seq_quittance')::text, 5, '0'),
    id_bail            integer      NOT NULL REFERENCES loc.bail,
    date_paiement      date         NOT NULL,
    montant            integer      NOT NULL,
    mode               varchar(40)  NOT NULL,   -- MODE_PAIEMENT
    reference_paiement varchar(80),             -- numéro de transaction mobile money, de chèque…
    notes              text,
    origine            varchar(40)  NOT NULL DEFAULT 'SAISIE',
    annule_le          timestamptz,
    annule_par         varchar(80),
    motif_annulation   varchar(250),
    cree_le            timestamptz  NOT NULL DEFAULT now(),
    cree_par           varchar(80),
    CONSTRAINT chk_encaissement_montant CHECK (montant > 0),
    CONSTRAINT chk_encaissement_annulation CHECK (annule_le IS NULL OR motif_annulation IS NOT NULL)
);
CREATE INDEX idx_encaissement_bail ON loc.encaissement (id_bail) WHERE annule_le IS NULL;
CREATE INDEX idx_encaissement_date ON loc.encaissement (date_paiement);
COMMENT ON TABLE loc.encaissement IS 'Paiements des loyers ; un encaissement erroné s''annule avec son motif, il ne se supprime pas';

-- -----------------------------------------------------------------------------
-- Production des échéances : chaque mois, pour chaque bail actif (§ 4.4)
-- -----------------------------------------------------------------------------
-- Rejouable sans risque : une échéance déjà produite n'est pas recréée. Un bail
-- terminé produit ses échéances jusqu'au mois de sa sortie, pas au-delà.
CREATE FUNCTION loc.generer_echeances(p_jusqua date DEFAULT current_date, p_bail integer DEFAULT NULL)
RETURNS integer LANGUAGE sql AS $$
    WITH nouvelles AS (
        INSERT INTO loc.echeance (id_bail, periode, date_exigibilite, loyer, charges, montant_du)
        SELECT b.id, m.periode::date,
               m.periode::date + (least(b.jour_exigibilite,
                    extract(day FROM (m.periode + interval '1 month' - interval '1 day'))::int) - 1),
               b.loyer, b.charges, b.loyer + b.charges
        FROM loc.bail b
        CROSS JOIN LATERAL generate_series(
            date_trunc('month', b.date_effet),
            date_trunc('month', least(p_jusqua, coalesce(b.date_sortie, p_jusqua))),
            interval '1 month') AS m(periode)
        WHERE (p_bail IS NULL OR b.id = p_bail)
          AND (b.statut IN ('ACTIF', 'PREAVIS') OR b.date_sortie IS NOT NULL)
        ON CONFLICT (id_bail, periode) DO NOTHING
        RETURNING 1
    )
    SELECT count(*)::int FROM nouvelles;
$$;
COMMENT ON FUNCTION loc.generer_echeances IS 'Produit les échéances manquantes jusqu''au mois de la date donnée ; rejouable';

-- -----------------------------------------------------------------------------
-- Situation de chaque échéance : réglée, partielle ou impayée, en retard ou non
-- -----------------------------------------------------------------------------
CREATE VIEW loc.v_echeance AS
WITH paye AS (
    SELECT id_bail, sum(montant)::bigint AS total
    FROM loc.encaissement WHERE annule_le IS NULL GROUP BY id_bail
), grace AS (
    SELECT coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'loyer.jours_de_grace'), 5) AS jours
), cumul AS (
    SELECT e.*, sum(e.montant_du) OVER (PARTITION BY e.id_bail ORDER BY e.periode) AS cumul
    FROM loc.echeance e
), affecte AS (
    SELECT c.*, least(c.montant_du, greatest(0, coalesce(p.total, 0) - (c.cumul - c.montant_du)))::int AS regle
    FROM cumul c LEFT JOIN paye p USING (id_bail)
)
SELECT a.id, a.id_bail, a.periode, a.date_exigibilite, a.loyer, a.charges, a.montant_du, a.regle,
       (a.montant_du - a.regle) AS reste,
       CASE WHEN a.regle = a.montant_du THEN 'A_JOUR' WHEN a.regle > 0 THEN 'PARTIEL' ELSE 'IMPAYE' END AS statut_paiement,
       (a.montant_du > a.regle AND a.date_exigibilite + g.jours < current_date) AS en_retard
FROM affecte a CROSS JOIN grace g;
COMMENT ON VIEW loc.v_echeance IS 'Chaque échéance et ce qui en est réglé : les paiements soldent les plus anciennes d''abord';

-- -----------------------------------------------------------------------------
-- Situation de chaque bail : dû à ce jour, payé, solde, retard
-- -----------------------------------------------------------------------------
CREATE VIEW loc.v_bail AS
SELECT b.id, b.reference, b.id_lot, l.numero AS numero_lot, l.etage, l.type AS type_lot, l.id_batiment,
       b.id_occupant, o.reference AS reference_occupant, btrim(o.nom || ' ' || coalesce(o.prenoms, '')) AS occupant,
       o.telephone AS telephone_occupant,
       b.date_effet, b.duree_mois, (b.date_effet + make_interval(months => b.duree_mois) - interval '1 day')::date AS date_fin,
       b.loyer, b.charges, b.caution, b.avance_mois, b.jour_exigibilite, b.statut,
       b.date_preavis, b.date_sortie, b.motif_fin, b.notes, b.cree_le, b.cree_par, b.maj_le, b.maj_par,
       coalesce(s.du, 0)::bigint         AS du_a_ce_jour,
       coalesce(p.total, 0)::bigint      AS paye,
       (coalesce(s.du, 0) - coalesce(p.total, 0))::bigint AS solde,
       coalesce(s.impaye, 0)::bigint     AS impaye,
       coalesce(s.mois_retard, 0)::int   AS mois_retard,
       s.retard_depuis
FROM loc.bail b
JOIN pat.lot l ON l.id = b.id_lot
JOIN loc.occupant o ON o.id = b.id_occupant
LEFT JOIN (
    SELECT id_bail,
           sum(montant_du) FILTER (WHERE date_exigibilite <= current_date) AS du,
           sum(reste) FILTER (WHERE en_retard)                             AS impaye,
           count(*) FILTER (WHERE en_retard)                               AS mois_retard,
           min(periode) FILTER (WHERE en_retard)                           AS retard_depuis
    FROM loc.v_echeance GROUP BY id_bail
) s ON s.id_bail = b.id
LEFT JOIN (
    SELECT id_bail, sum(montant) AS total FROM loc.encaissement WHERE annule_le IS NULL GROUP BY id_bail
) p ON p.id_bail = b.id;
COMMENT ON VIEW loc.v_bail IS 'Chaque bail : dû à ce jour, payé, solde (négatif : avance), impayés en retard, mois de retard';

-- -----------------------------------------------------------------------------
-- Le bâtiment porte désormais sa situation de paiement (thématique « Impayés », § 4.1)
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
       coalesce(x.loyers, 0)::bigint    AS loyers_baux
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
) x ON true;
