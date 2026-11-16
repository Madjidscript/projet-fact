-- =============================================================================
-- Étapes 14 et 15 : annonces publiées depuis le SIG (§ 4.10), demandes venues du site
-- (§ 3.3 à 3.5, § 3.8), alertes de recherche des visiteurs (§ 3.7), messages à envoyer.
-- =============================================================================

INSERT INTO ref.valeur (domaine, code, libelle, ordre) VALUES
    ('STATUT_ANNONCE', 'BROUILLON', 'Brouillon', 1),
    ('STATUT_ANNONCE', 'A_VALIDER', 'À valider', 2),
    ('STATUT_ANNONCE', 'PUBLIEE',   'En ligne', 3),
    ('STATUT_ANNONCE', 'REFUSEE',   'À revoir', 4),
    ('STATUT_ANNONCE', 'RETIREE',   'Retirée', 5)
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Annonces : une annonce présente un lot sur le site
-- -----------------------------------------------------------------------------
CREATE SEQUENCE web.seq_annonce;

CREATE TABLE web.annonce (
    id              integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference       varchar(20)  NOT NULL UNIQUE DEFAULT 'ANN-' || lpad(nextval('web.seq_annonce')::text, 4, '0'),
    id_lot          integer      NOT NULL REFERENCES pat.lot,
    operation       varchar(10)  NOT NULL DEFAULT 'LOCATION',  -- OPERATION : LOCATION, VENTE
    titre           varchar(160) NOT NULL,
    slug            varchar(200) NOT NULL UNIQUE,              -- l'adresse de la fiche sur le site
    description     text,
    prix            integer      NOT NULL,                     -- loyer mensuel ou prix de vente, FCFA
    charges         integer      NOT NULL DEFAULT 0,
    caution_mois    smallint     NOT NULL DEFAULT 0,
    avance_mois     smallint     NOT NULL DEFAULT 0,
    position_exacte boolean      NOT NULL DEFAULT false,       -- faux : zone approximative (§ 3.2)
    a_la_une        boolean      NOT NULL DEFAULT false,
    statut          varchar(20)  NOT NULL DEFAULT 'BROUILLON',
    motif           varchar(250),                              -- motif d'un refus ou d'un retrait
    soumise_le      timestamptz,
    soumise_par     varchar(80),
    publiee_le      timestamptz,
    publiee_par     varchar(80),
    retiree_le      timestamptz,
    retiree_par     varchar(80),
    cree_le         timestamptz  NOT NULL DEFAULT now(),
    cree_par        varchar(80),
    maj_le          timestamptz,
    maj_par         varchar(80),
    CONSTRAINT chk_annonce_operation CHECK (operation IN ('LOCATION', 'VENTE')),
    CONSTRAINT chk_annonce_statut CHECK (statut IN ('BROUILLON', 'A_VALIDER', 'PUBLIEE', 'REFUSEE', 'RETIREE')),
    CONSTRAINT chk_annonce_montants CHECK (prix > 0 AND charges >= 0 AND caution_mois BETWEEN 0 AND 24 AND avance_mois BETWEEN 0 AND 24),
    CONSTRAINT chk_annonce_titre CHECK (btrim(titre) <> '')
);
-- Une seule annonce vivante par lot
CREATE UNIQUE INDEX uq_annonce_lot ON web.annonce (id_lot) WHERE statut IN ('BROUILLON', 'A_VALIDER', 'PUBLIEE', 'REFUSEE');
CREATE INDEX idx_annonce_statut ON web.annonce (statut);
COMMENT ON TABLE web.annonce IS 'Annonces du site : un lot proposé à la location ou à la vente (§ 4.10)';

CREATE TABLE web.annonce_photo (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_annonce  integer      NOT NULL REFERENCES web.annonce ON DELETE CASCADE,
    chemin      varchar(300) NOT NULL,
    largeur     integer,
    hauteur     integer,
    ordre       smallint     NOT NULL DEFAULT 0,
    legende     varchar(160),
    cree_le     timestamptz  NOT NULL DEFAULT now(),
    cree_par    varchar(80)
);
CREATE INDEX idx_annonce_photo ON web.annonce_photo (id_annonce, ordre);

-- À la signature d'un bail (lot occupé), à l'archivage ou à la sortie de gestion, l'annonce quitte le site.
CREATE FUNCTION web.retirer_annonce_du_lot() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF (NEW.statut IN ('OCCUPE', 'EN_PREAVIS', 'HORS_GESTION') AND NEW.statut IS DISTINCT FROM OLD.statut)
       OR (NEW.archive_le IS NOT NULL AND OLD.archive_le IS NULL) THEN
        UPDATE web.annonce
           SET statut = 'RETIREE', retiree_le = now(), retiree_par = 'Automatique',
               motif = CASE WHEN NEW.statut IN ('OCCUPE', 'EN_PREAVIS') THEN 'Bien loué ou vendu'
                            WHEN NEW.archive_le IS NOT NULL THEN 'Lot archivé' ELSE 'Lot sorti de la gestion' END
         WHERE id_lot = NEW.id AND statut IN ('BROUILLON', 'A_VALIDER', 'PUBLIEE', 'REFUSEE');
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_lot_retire_annonce AFTER UPDATE OF statut, archive_le ON pat.lot
    FOR EACH ROW EXECUTE FUNCTION web.retirer_annonce_du_lot();

/** La famille de bien que le visiteur recherche (§ 3.2) : les F2 à F5 sont des « Appartement ». */
CREATE FUNCTION web.categorie(p_type text) RETURNS text LANGUAGE sql IMMUTABLE AS $$
    SELECT CASE p_type
        WHEN 'STUDIO' THEN 'Studio' WHEN 'VILLA' THEN 'Villa' WHEN 'DUPLEX' THEN 'Duplex'
        WHEN 'BUREAU' THEN 'Bureau' WHEN 'MAGASIN' THEN 'Magasin' WHEN 'ENTREPOT' THEN 'Entrepôt'
        WHEN 'TERRAIN' THEN 'Terrain' WHEN 'IMMEUBLE' THEN 'Immeuble'
        ELSE 'Appartement' END
$$;

-- La vue publique : ce que le site peut montrer d'une annonce en ligne. La position exacte
-- n'est donnée que si l'agence l'a choisie ; sinon un point du quartier, décalé de façon stable.
CREATE VIEW web.v_annonce AS
SELECT a.id, a.reference, a.slug, a.id_lot, a.operation, a.titre, a.description, a.prix, a.charges,
       a.caution_mois, a.avance_mois, a.position_exacte, a.a_la_une, a.statut, a.motif,
       a.soumise_le, a.soumise_par, a.publiee_le, a.publiee_par, a.retiree_le, a.retiree_par,
       a.cree_le, a.cree_par, a.maj_le, a.maj_par,
       l.numero AS numero_lot, l.type AS type_lot, web.categorie(l.type) AS categorie,
       (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = l.type) AS libelle_type,
       l.surface, l.pieces, l.chambres, l.salles_eau, l.etage, l.equipements, l.statut AS statut_lot,
       b.id AS id_batiment, b.nom AS batiment, b.id_commune, c.libelle AS commune, b.id_quartier, q.libelle AS quartier,
       b.id_gestionnaire,
       ST_Y(b.geom) AS latitude_exacte, ST_X(b.geom) AS longitude_exacte,
       CASE WHEN a.position_exacte THEN ST_Y(b.geom)
            ELSE round((ST_Y(b.geom) + ((hashtext(a.reference) % 1000) / 1000.0) * 0.004)::numeric, 3)::float8 END AS latitude,
       CASE WHEN a.position_exacte THEN ST_X(b.geom)
            ELSE round((ST_X(b.geom) + ((hashtext(a.reference || 'x') % 1000) / 1000.0) * 0.004)::numeric, 3)::float8 END AS longitude,
       (SELECT count(*) FROM web.annonce_photo p WHERE p.id_annonce = a.id) AS photos
FROM web.annonce a
JOIN pat.lot l ON l.id = a.id_lot
JOIN pat.batiment b ON b.id = l.id_batiment
JOIN adm.commune c ON c.id_commune = b.id_commune
LEFT JOIN adm.quartier q ON q.id_quartier = b.id_quartier;

-- -----------------------------------------------------------------------------
-- Demandes venues du site (§ 3.8 : un tableau unique)
-- -----------------------------------------------------------------------------
CREATE SEQUENCE web.seq_demande;

CREATE TABLE web.demande (
    id                 integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    reference          varchar(20)  NOT NULL UNIQUE DEFAULT 'DEM-' || lpad(nextval('web.seq_demande')::text, 4, '0'),
    type               varchar(20)  NOT NULL,                    -- TYPE_DEMANDE
    statut             varchar(20)  NOT NULL DEFAULT 'NOUVELLE', -- STATUT_DEMANDE
    id_annonce         integer      REFERENCES web.annonce,
    id_batiment        integer      REFERENCES pat.batiment,     -- pour la visite : le bâtiment du bien
    id_compte          integer      REFERENCES app.compte,       -- qui la traite
    nom                varchar(160) NOT NULL,
    telephone          varchar(30)  NOT NULL,
    email              varchar(160),
    date_souhaitee     date,
    creneau            varchar(40),
    message            text,
    details            jsonb        NOT NULL DEFAULT '{}',       -- les champs propres au formulaire
    latitude           double precision,
    longitude          double precision,
    consentement       boolean      NOT NULL DEFAULT false,
    note               text,                                     -- suivi interne
    rdv_le             timestamptz,
    cree_le            timestamptz  NOT NULL DEFAULT now(),
    maj_le             timestamptz,
    maj_par            varchar(80),
    CONSTRAINT chk_demande_type CHECK (type IN ('VISITE', 'MANDAT', 'DEVIS', 'ESTIMATION', 'CONTACT')),
    CONSTRAINT chk_demande_statut CHECK (statut IN ('NOUVELLE', 'PRISE_EN_CHARGE', 'RENDEZ_VOUS', 'CONCLUE', 'SANS_SUITE')),
    CONSTRAINT chk_demande_nom CHECK (btrim(nom) <> ''),
    CONSTRAINT chk_demande_telephone CHECK (telephone ~ '^\+?[0-9 .]{8,20}$'),
    CONSTRAINT chk_demande_email CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[a-zA-Z]{2,}$')
);
CREATE INDEX idx_demande_statut ON web.demande (statut, cree_le DESC);
COMMENT ON TABLE web.demande IS 'Demandes du site : visite, confier un bien, devis, estimation, contact (§ 3.8)';

CREATE TABLE web.demande_piece (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_demande  integer      NOT NULL REFERENCES web.demande ON DELETE CASCADE,
    chemin      varchar(300) NOT NULL,
    nom         varchar(200) NOT NULL,
    type        varchar(80)  NOT NULL,
    taille      integer      NOT NULL,
    cree_le     timestamptz  NOT NULL DEFAULT now()
);

-- -----------------------------------------------------------------------------
-- Alertes de recherche des visiteurs (§ 3.7)
-- -----------------------------------------------------------------------------
CREATE TABLE web.abonnement (
    id             integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contact        varchar(160) NOT NULL,          -- e-mail ou numéro WhatsApp
    canal          varchar(10)  NOT NULL,          -- EMAIL, WHATSAPP
    criteres       jsonb        NOT NULL,          -- operation, types[], idCommune, idQuartier, prixMin, prixMax, pieces, surface
    jeton          varchar(40)  NOT NULL UNIQUE,   -- lien de désabonnement
    cree_le        timestamptz  NOT NULL DEFAULT now(),
    desabonne_le   timestamptz,
    CONSTRAINT chk_abonnement_canal CHECK (canal IN ('EMAIL', 'WHATSAPP'))
);

-- -----------------------------------------------------------------------------
-- Messages à envoyer : accusés de réception, alertes aux abonnés. Tant que l'agence n'a pas
-- de compte d'envoi (e-mail, SMS, WhatsApp), ils attendent ici et l'agent les envoie d'un geste.
-- -----------------------------------------------------------------------------
CREATE TABLE web.message (
    id            integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    canal         varchar(10)  NOT NULL,           -- EMAIL, WHATSAPP, SMS
    destinataire  varchar(160) NOT NULL,
    sujet         varchar(200),
    corps         text         NOT NULL,
    motif         varchar(40)  NOT NULL,           -- ACCUSE, ALERTE_RECHERCHE
    id_demande    integer      REFERENCES web.demande ON DELETE CASCADE,
    id_annonce    integer      REFERENCES web.annonce,
    cree_le       timestamptz  NOT NULL DEFAULT now(),
    envoye_le     timestamptz,
    envoye_par    varchar(80),
    abandonne_le  timestamptz,
    CONSTRAINT chk_message_canal CHECK (canal IN ('EMAIL', 'WHATSAPP', 'SMS'))
);
CREATE INDEX idx_message_attente ON web.message (cree_le) WHERE envoye_le IS NULL AND abandonne_le IS NULL;

INSERT INTO ref.reglage (cle, valeur, libelle, unite) VALUES
    ('demande.taille_pieces_mo', '10', 'Taille totale des pièces jointes d''une demande', 'Mo')
ON CONFLICT DO NOTHING;

-- -----------------------------------------------------------------------------
-- Les trois annonces du site actuel (reprise, V4) sont en ligne d'emblée.
-- -----------------------------------------------------------------------------
INSERT INTO web.annonce (id_lot, operation, titre, slug, description, prix, statut, publiee_le, publiee_par, cree_par)
SELECT l.id, 'LOCATION', b.nom,
       regexp_replace(lower(unaccent(b.nom)), '[^a-z0-9]+', '-', 'g') || '-' || l.id,
       l.description, l.loyer_reference, 'PUBLIEE', now(), 'Reprise du site', 'Reprise du site'
FROM pat.lot l JOIN pat.batiment b ON b.id = l.id_batiment
WHERE l.cree_par = 'Reprise du site' AND l.loyer_reference > 0 AND l.archive_le IS NULL
  AND l.statut IN ('VACANT', 'A_LOUER');

-- -----------------------------------------------------------------------------
-- Alertes internes : nouvelle demande, demande sans réponse, annonce à valider
-- -----------------------------------------------------------------------------
ALTER TABLE app.alerte DROP CONSTRAINT chk_alerte_type;
ALTER TABLE app.alerte ADD CONSTRAINT chk_alerte_type CHECK (type IN ('BAIL_ECHEANCE', 'LOYER_RETARD', 'LOT_VACANT',
    'MANDAT_ECHEANCE', 'INTERVENTION_URGENTE', 'SANS_GESTIONNAIRE', 'DEMANDE_NOUVELLE', 'DEMANDE_EN_ATTENTE', 'ANNONCE_A_VALIDER'));

/** Heures ouvrées (lundi à vendredi) écoulées depuis un instant. */
CREATE FUNCTION web.heures_ouvrees(p_depuis timestamptz) RETURNS numeric LANGUAGE sql STABLE AS $$
    SELECT coalesce(sum(extract(epoch FROM (least(h + interval '1 hour', now()) - greatest(h, p_depuis))) / 3600), 0)::numeric
    FROM generate_series(date_trunc('hour', p_depuis), now(), interval '1 hour') h
    WHERE extract(isodow FROM h) < 6
$$;

CREATE FUNCTION app.generer_alertes_web() RETURNS integer LANGUAGE plpgsql AS $$
DECLARE
    n      integer := 0;
    k      integer;
    delai  integer := coalesce((SELECT valeur::int FROM ref.reglage WHERE cle = 'demande.delai_prise_en_charge_heures'), 24);
BEGIN
    -- Nouvelle demande : au gestionnaire du bâtiment (visite) ou à la Direction, tout de suite
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT d.dest, 'DEMANDE_NOUVELLE', x.id::text, 'ATTENTION',
           (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_DEMANDE' AND code = x.type) || ' : ' || x.nom,
           x.reference || coalesce(', ' || a.titre, '') || '. Rappelez au ' || x.telephone || '.',
           '/demandes?demande=' || x.id, x.id_batiment
    FROM web.demande x
    LEFT JOIN web.annonce a ON a.id = x.id_annonce
    CROSS JOIN LATERAL (
        SELECT x.id_compte AS dest WHERE x.id_compte IS NOT NULL
        UNION SELECT dd FROM app.direction() dd
    ) d
    WHERE x.statut = 'NOUVELLE' AND x.cree_le >= now() - interval '7 days'
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- Restée « Nouvelle » plus de 24 heures ouvrées : la Direction est prévenue
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT dd, 'DEMANDE_EN_ATTENTE', x.id::text, 'CRITIQUE',
           'Demande sans réponse : ' || x.nom,
           x.reference || ' attend depuis le ' || to_char(x.cree_le AT TIME ZONE 'Africa/Abidjan', 'DD/MM/YYYY à HH24"h"MI')
               || '. Personne ne l''a encore prise en charge.',
           '/demandes?demande=' || x.id, x.id_batiment
    FROM web.demande x CROSS JOIN app.direction() dd
    WHERE x.statut = 'NOUVELLE' AND web.heures_ouvrees(x.cree_le) > delai
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- Annonce à valider : éditeurs web et Direction
    INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
    SELECT c.id, 'ANNONCE_A_VALIDER', a.id || '-' || to_char(a.soumise_le, 'YYYYMMDDHH24MISS'), 'INFO',
           'Annonce à valider : ' || a.titre,
           a.reference || ', proposée par ' || coalesce(a.soumise_par, '-') || '. Vérifiez-la avant sa mise en ligne.',
           '/annonces?annonce=' || a.id, a.id_batiment
    FROM web.v_annonce a
    JOIN app.compte c ON c.actif AND c.profil IN ('EDITEUR', 'DIRECTION', 'ADMIN')
    WHERE a.statut = 'A_VALIDER'
    ON CONFLICT DO NOTHING;
    GET DIAGNOSTICS k = ROW_COUNT; n := n + k;

    -- Résolues : demande prise en charge, annonce traitée
    UPDATE app.alerte al SET resolue_le = now()
    WHERE al.resolue_le IS NULL AND al.type IN ('DEMANDE_NOUVELLE', 'DEMANDE_EN_ATTENTE')
      AND NOT EXISTS (SELECT 1 FROM web.demande x WHERE x.id = al.cle::int AND x.statut = 'NOUVELLE');
    UPDATE app.alerte al SET resolue_le = now()
    WHERE al.resolue_le IS NULL AND al.type = 'ANNONCE_A_VALIDER'
      AND NOT EXISTS (SELECT 1 FROM web.annonce a WHERE a.id = split_part(al.cle, '-', 1)::int AND a.statut = 'A_VALIDER');
    RETURN n;
END;
$$;
