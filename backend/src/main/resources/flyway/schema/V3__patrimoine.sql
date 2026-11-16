-- =============================================================================
-- FACT Immobilier — Patrimoine : propriétaires, bâtiments, lots
-- Étape 4 du plan de réalisation (SFD-FACT-2026-01, § 4.2 et § 7.1).
-- -----------------------------------------------------------------------------
-- « Un propriétaire possède un ou plusieurs bâtiments, chaque bâtiment est
-- positionné sur la carte et contient des lots » (cahier des charges, § 8.1).
-- Aucune suppression définitive (§ 8) : une fiche est archivée (archive_le) et
-- reste consultable. Les listes de valeurs (type, statut…) sont contrôlées par
-- l'API contre ref.valeur, que l'administrateur peut enrichir.
-- =============================================================================

CREATE SEQUENCE pat.seq_proprietaire;
CREATE SEQUENCE pat.seq_batiment;

-- -----------------------------------------------------------------------------
-- Propriétaires (mandants)
-- -----------------------------------------------------------------------------
CREATE TABLE pat.proprietaire (
    id                      integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference               varchar(20)  NOT NULL UNIQUE
                            DEFAULT 'PRO-' || lpad(nextval('pat.seq_proprietaire')::text, 4, '0'),
    type_personne           varchar(40)  NOT NULL DEFAULT 'PHYSIQUE',
    nom                     varchar(160) NOT NULL,       -- nom, ou raison sociale d'une personne morale
    prenoms                 varchar(160),
    telephone               varchar(30),
    telephone_2             varchar(30),
    email                   varchar(160),
    adresse                 varchar(250),
    piece_type              varchar(40),
    piece_numero            varchar(60),
    reversement_mode        varchar(40),                 -- MODE_PAIEMENT : comment lui reverser ses loyers
    reversement_coordonnees varchar(200),                -- numéro mobile money, RIB…
    id_compte               integer UNIQUE REFERENCES app.compte,   -- son accès à l'espace propriétaire
    notes                   text,
    origine                 varchar(40)  NOT NULL DEFAULT 'SAISIE',
    archive_le              timestamptz,
    cree_le                 timestamptz  NOT NULL DEFAULT now(),
    cree_par                varchar(80),
    maj_le                  timestamptz,
    maj_par                 varchar(80),
    CONSTRAINT chk_proprietaire_nom CHECK (btrim(nom) <> ''),
    CONSTRAINT chk_proprietaire_telephone CHECK (telephone IS NULL OR telephone ~ '^\+?[0-9 ]{8,19}$'),
    CONSTRAINT chk_proprietaire_telephone_2 CHECK (telephone_2 IS NULL OR telephone_2 ~ '^\+?[0-9 ]{8,19}$'),
    CONSTRAINT chk_proprietaire_email CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[a-zA-Z]{2,}$')
);
CREATE INDEX idx_proprietaire_nom ON pat.proprietaire (lower(nom));

COMMENT ON TABLE  pat.proprietaire           IS 'Propriétaires mandants : ils possèdent les bâtiments gérés par l''agence';
COMMENT ON COLUMN pat.proprietaire.id_compte IS 'Compte de l''espace propriétaire (§ 4.9), s''il en a un';

-- -----------------------------------------------------------------------------
-- Bâtiments
-- -----------------------------------------------------------------------------
-- « geom » peut être nul : un bâtiment importé sans coordonnées attend dans la
-- liste « À localiser » le passage d'un agent (§ 7.3). La saisie manuelle, elle,
-- exige la position (§ 4.2) : c'est l'API qui applique cette règle.
CREATE TABLE pat.batiment (
    id                 integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference          varchar(20)  NOT NULL UNIQUE
                       DEFAULT 'BAT-' || lpad(nextval('pat.seq_batiment')::text, 4, '0'),
    nom                varchar(160) NOT NULL,
    type               varchar(40)  NOT NULL,            -- TYPE_BATIMENT
    adresse            varchar(250),
    id_commune         integer      NOT NULL REFERENCES adm.commune,
    id_quartier        integer      REFERENCES adm.quartier,
    geom               geometry(Point, 4326),
    emprise            geometry(Polygon, 4326),          -- emprise au sol, dessinée à fort zoom (§ 4.1)
    precision_gps      numeric(6,1),                     -- en mètres, telle que le téléphone l'annonce
    niveaux            smallint,
    annee_construction smallint,
    description        text,
    id_proprietaire    integer      NOT NULL REFERENCES pat.proprietaire,
    id_gestionnaire    integer      REFERENCES app.compte,
    id_suppleant       integer      REFERENCES app.compte,
    validation         varchar(20)  NOT NULL DEFAULT 'VALIDE',
    origine            varchar(40)  NOT NULL DEFAULT 'SAISIE',
    archive_le         timestamptz,
    cree_le            timestamptz  NOT NULL DEFAULT now(),
    cree_par           varchar(80),
    maj_le             timestamptz,
    maj_par            varchar(80),
    CONSTRAINT chk_batiment_nom CHECK (btrim(nom) <> ''),
    CONSTRAINT chk_batiment_niveaux CHECK (niveaux IS NULL OR niveaux BETWEEN 0 AND 60),
    CONSTRAINT chk_batiment_annee CHECK (annee_construction IS NULL OR annee_construction BETWEEN 1900 AND 2100),
    CONSTRAINT chk_batiment_validation CHECK (validation IN ('VALIDE', 'A_VALIDER')),
    -- Garde-fou contre une latitude et une longitude inversées : le point doit tomber en Côte d'Ivoire.
    CONSTRAINT chk_batiment_position CHECK (geom IS NULL
        OR (ST_X(geom) BETWEEN -8.7 AND -2.4 AND ST_Y(geom) BETWEEN 4.2 AND 10.8)),
    CONSTRAINT chk_batiment_suppleant CHECK (id_suppleant IS NULL OR id_suppleant <> id_gestionnaire)
);
CREATE INDEX idx_batiment_geom         ON pat.batiment USING gist (geom);
CREATE INDEX idx_batiment_proprietaire ON pat.batiment (id_proprietaire);
CREATE INDEX idx_batiment_gestionnaire ON pat.batiment (id_gestionnaire);
CREATE INDEX idx_batiment_commune      ON pat.batiment (id_commune);

COMMENT ON TABLE  pat.batiment            IS 'Bâtiments, villas, terrains et chantiers gérés : un point sur la carte, un propriétaire, un gestionnaire';
COMMENT ON COLUMN pat.batiment.validation IS 'A_VALIDER : fiche créée sur le terrain ou reprise, en attente du contrôle du gestionnaire (§ 4.6)';
COMMENT ON COLUMN pat.batiment.geom       IS 'Position GPS ; nulle tant que le bâtiment est « à localiser » (§ 7.3)';

-- -----------------------------------------------------------------------------
-- Lots : appartements, magasins, bureaux d'un bâtiment
-- -----------------------------------------------------------------------------
CREATE TABLE pat.lot (
    id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_batiment     integer      NOT NULL REFERENCES pat.batiment,
    numero          varchar(20)  NOT NULL,               -- numéro de porte
    etage           smallint     NOT NULL DEFAULT 0,     -- 0 : rez-de-chaussée
    type            varchar(40)  NOT NULL,               -- TYPE_LOT
    surface         numeric(8,1),
    pieces          smallint,
    chambres        smallint,
    salles_eau      smallint,
    equipements     text[]       NOT NULL DEFAULT '{}',  -- codes EQUIPEMENT
    loyer_reference integer,                             -- FCFA, sans décimale (§ 8)
    charges         integer,
    statut          varchar(40)  NOT NULL DEFAULT 'VACANT',   -- STATUT_LOT
    statut_depuis   date         NOT NULL DEFAULT current_date,
    description     text,
    archive_le      timestamptz,
    cree_le         timestamptz  NOT NULL DEFAULT now(),
    cree_par        varchar(80),
    maj_le          timestamptz,
    maj_par         varchar(80),
    CONSTRAINT chk_lot_numero CHECK (btrim(numero) <> ''),
    CONSTRAINT chk_lot_etage CHECK (etage BETWEEN -5 AND 60),
    -- « Aucune valeur négative » (§ 3.2) : le site actuel affiche une salle de bain à « -2 ».
    CONSTRAINT chk_lot_surface CHECK (surface IS NULL OR surface > 0),
    CONSTRAINT chk_lot_pieces CHECK ((pieces IS NULL OR pieces >= 0) AND (chambres IS NULL OR chambres >= 0)
                                     AND (salles_eau IS NULL OR salles_eau >= 0)),
    CONSTRAINT chk_lot_montants CHECK ((loyer_reference IS NULL OR loyer_reference >= 0) AND (charges IS NULL OR charges >= 0))
);
-- « Le numéro de porte est unique dans un bâtiment » (§ 4.2) ; un lot archivé libère son numéro.
CREATE UNIQUE INDEX uq_lot_numero ON pat.lot (id_batiment, lower(numero)) WHERE archive_le IS NULL;
CREATE INDEX idx_lot_batiment ON pat.lot (id_batiment);
CREATE INDEX idx_lot_statut   ON pat.lot (statut) WHERE archive_le IS NULL;

COMMENT ON TABLE  pat.lot               IS 'Lots d''un bâtiment : porte, étage, type, surface, loyer de référence, statut (§ 4.2)';
COMMENT ON COLUMN pat.lot.statut_depuis IS 'Date du dernier changement de statut : sert à mesurer la durée de vacance (§ 4.8)';

-- Le changement de statut d'un lot remet son ancienneté à zéro.
CREATE FUNCTION pat.dater_statut_lot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.statut IS DISTINCT FROM OLD.statut THEN
        NEW.statut_depuis := current_date;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER trg_lot_statut BEFORE UPDATE ON pat.lot
    FOR EACH ROW EXECUTE FUNCTION pat.dater_statut_lot();

-- -----------------------------------------------------------------------------
-- Vue de synthèse : un bâtiment et le décompte de ses lots
-- -----------------------------------------------------------------------------
CREATE VIEW pat.v_batiment AS
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
       coalesce(l.loyers, 0)    AS loyers_reference
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
) l ON true;

COMMENT ON VIEW pat.v_batiment IS 'Un bâtiment avec sa commune, son propriétaire, son gestionnaire et le décompte de ses lots';
