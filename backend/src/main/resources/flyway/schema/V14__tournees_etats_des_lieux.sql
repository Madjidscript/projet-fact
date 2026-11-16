-- =============================================================================
-- V14 : le travail de l'agent sur le terrain (§ 4.6)
--   - la tournée du jour : bâtiments à visiter, pointage sur place, compte rendu ;
--   - l'état des lieux : pièce par pièce, photos, deux signatures au doigt, PDF ;
--   - l'encaissement sur place : envoi rejouable sans doublon, reçu envoyé au locataire.
-- =============================================================================

CREATE SCHEMA IF NOT EXISTS ter;
COMMENT ON SCHEMA ter IS 'Travail de terrain : tournées des agents';

CREATE SEQUENCE ter.seq_tournee;
CREATE SEQUENCE loc.seq_etat_des_lieux;

-- -----------------------------------------------------------------------------
-- Tournées
-- -----------------------------------------------------------------------------
CREATE TABLE ter.tournee (
    id             integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference      varchar(20)  NOT NULL UNIQUE
                   DEFAULT 'TOU-' || lpad(nextval('ter.seq_tournee')::text, 4, '0'),
    id_agent       integer      NOT NULL REFERENCES app.compte,
    jour           date         NOT NULL,
    statut         varchar(20)  NOT NULL DEFAULT 'PREVUE',
    consigne       varchar(500),
    compte_rendu   text,
    demarree_le    timestamptz,
    terminee_le    timestamptz,
    cree_le        timestamptz  NOT NULL DEFAULT now(),
    cree_par       varchar(80),
    maj_le         timestamptz,
    maj_par        varchar(80),
    CONSTRAINT uq_tournee_agent_jour UNIQUE (id_agent, jour),
    CONSTRAINT chk_tournee_statut CHECK (statut IN ('PREVUE', 'EN_COURS', 'TERMINEE'))
);
COMMENT ON TABLE ter.tournee IS 'La tournée d''un agent pour un jour : une par agent et par jour';

CREATE TABLE ter.etape (
    id             integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_tournee     integer      NOT NULL REFERENCES ter.tournee ON DELETE CASCADE,
    id_batiment    integer      NOT NULL REFERENCES pat.batiment,
    ordre          smallint     NOT NULL DEFAULT 0,
    motif          varchar(30)  NOT NULL DEFAULT 'VISITE',
    consigne       varchar(250),
    pointee_le     timestamptz,
    latitude       double precision,
    longitude      double precision,
    precision_gps  real,
    distance_m     integer,
    resultat       varchar(20),
    compte_rendu   text,
    CONSTRAINT uq_etape UNIQUE (id_tournee, id_batiment),
    CONSTRAINT chk_etape_motif CHECK (motif IN ('VISITE', 'RECOUVREMENT', 'ETAT_DES_LIEUX', 'CONTROLE', 'TRAVAUX')),
    CONSTRAINT chk_etape_resultat CHECK (resultat IS NULL OR resultat IN ('FAIT', 'ABSENT', 'REPORTE'))
);
CREATE INDEX idx_etape_batiment ON ter.etape (id_batiment);
COMMENT ON COLUMN ter.etape.distance_m IS 'Distance entre le point du pointage et le bâtiment : preuve de passage sur place';

-- -----------------------------------------------------------------------------
-- États des lieux
-- -----------------------------------------------------------------------------
CREATE TABLE loc.etat_des_lieux (
    id                    integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference             varchar(20)  NOT NULL UNIQUE
                          DEFAULT 'EDL-' || lpad(nextval('loc.seq_etat_des_lieux')::text, 4, '0'),
    cle                   uuid         NOT NULL UNIQUE,
    id_bail               integer      NOT NULL REFERENCES loc.bail,
    type                  varchar(10)  NOT NULL,
    fait_le               timestamptz  NOT NULL,
    pieces                jsonb        NOT NULL DEFAULT '[]',
    compteurs             jsonb        NOT NULL DEFAULT '{}',
    cles_remises          smallint,
    observations          text,
    signataire            varchar(160) NOT NULL,
    signature_occupant    varchar(300) NOT NULL,
    signature_agent       varchar(300) NOT NULL,
    latitude              double precision,
    longitude             double precision,
    cree_le               timestamptz  NOT NULL DEFAULT now(),
    cree_par              varchar(80)  NOT NULL,
    CONSTRAINT uq_etat_des_lieux UNIQUE (id_bail, type),
    CONSTRAINT chk_edl_type CHECK (type IN ('ENTREE', 'SORTIE'))
);
COMMENT ON TABLE  loc.etat_des_lieux        IS 'État des lieux d''entrée ou de sortie d''un bail, signé sur place par l''occupant et l''agent';
COMMENT ON COLUMN loc.etat_des_lieux.pieces IS '[{piece, elements: [{nom, etat (BON, USAGE, MAUVAIS, HORS_SERVICE), note}], note}]';

CREATE TABLE loc.etat_des_lieux_photo (
    id        integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_edl    integer      NOT NULL REFERENCES loc.etat_des_lieux ON DELETE CASCADE,
    piece     varchar(80)  NOT NULL,
    chemin    varchar(300) NOT NULL,
    ordre     smallint     NOT NULL DEFAULT 0
);
CREATE INDEX idx_edl_photo ON loc.etat_des_lieux_photo (id_edl);

-- -----------------------------------------------------------------------------
-- Encaissement sur place : rejouable, et reçu consultable par le locataire
-- -----------------------------------------------------------------------------
ALTER TABLE loc.encaissement
    ADD COLUMN cle        uuid UNIQUE,
    ADD COLUMN jeton_recu uuid NOT NULL DEFAULT gen_random_uuid(),
    ADD COLUMN latitude   double precision,
    ADD COLUMN longitude  double precision;
CREATE UNIQUE INDEX uq_encaissement_jeton ON loc.encaissement (jeton_recu);
COMMENT ON COLUMN loc.encaissement.cle        IS 'Clé tirée sur le téléphone : un envoi rejoué hors connexion ne crée pas deux paiements';
COMMENT ON COLUMN loc.encaissement.jeton_recu IS 'Jeton du lien envoyé au locataire pour ouvrir son reçu sans compte';

ALTER TABLE web.message ADD COLUMN id_encaissement bigint REFERENCES loc.encaissement;

-- Pour la tournée : l'agence, point de départ par défaut (Cocody, Riviera 3).
INSERT INTO ref.reglage (cle, valeur, libelle, unite) VALUES
    ('agence.latitude', '5.3600', 'Latitude de l''agence (départ des tournées)', '°'),
    ('agence.longitude', '-3.9600', 'Longitude de l''agence (départ des tournées)', '°'),
    ('tournee.distance_pointage_m', '250', 'Distance au-delà de laquelle un pointage est signalé comme éloigné', 'm')
ON CONFLICT DO NOTHING;

-- Le compte rendu d'une tournée terminée est signalé aux gestionnaires des bâtiments visités.
ALTER TABLE app.alerte DROP CONSTRAINT chk_alerte_type;
ALTER TABLE app.alerte ADD CONSTRAINT chk_alerte_type CHECK (type IN ('BAIL_ECHEANCE', 'LOYER_RETARD', 'LOT_VACANT',
    'MANDAT_ECHEANCE', 'INTERVENTION_URGENTE', 'SANS_GESTIONNAIRE', 'DEMANDE_NOUVELLE', 'DEMANDE_EN_ATTENTE',
    'ANNONCE_A_VALIDER', 'AFFECTATION', 'DEVIS_A_APPROUVER', 'ACCORD_PROPRIETAIRE', 'RELEVE_DISPONIBLE', 'TOURNEE'));
