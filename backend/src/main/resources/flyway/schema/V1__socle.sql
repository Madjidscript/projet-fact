-- =============================================================================
-- FACT Immobilier — Socle : extensions, schémas, nomenclatures, plateforme
-- Migration Flyway. Étape 1 du plan de réalisation (SFD-FACT-2026-01, § 9).
-- -----------------------------------------------------------------------------
-- Stockage en EPSG:4326 (WGS 84, celui du GPS des téléphones), calculs
-- métriques en EPSG:32630 (UTM 30 N, zone d'Abidjan).
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS unaccent;   -- recherche sans accent (quartiers, locataires)

CREATE SCHEMA IF NOT EXISTS adm;   -- référentiel administratif : communes, quartiers
CREATE SCHEMA IF NOT EXISTS ref;   -- nomenclatures (listes de valeurs) et paramètres
CREATE SCHEMA IF NOT EXISTS pat;   -- patrimoine : propriétaires, bâtiments, lots, mandats
CREATE SCHEMA IF NOT EXISTS loc;   -- location : occupants, baux, échéances, encaissements
CREATE SCHEMA IF NOT EXISTS tra;   -- interventions et chantiers
CREATE SCHEMA IF NOT EXISTS web;   -- annonces publiées et demandes venues du site
CREATE SCHEMA IF NOT EXISTS app;   -- plateforme : comptes, droits, journal
CREATE SCHEMA IF NOT EXISTS tdb;   -- vues de restitution des tableaux de bord

COMMENT ON SCHEMA adm IS 'Référentiel administratif : communes et quartiers du District d''Abidjan';
COMMENT ON SCHEMA ref IS 'Nomenclatures (listes de valeurs) et paramètres, modifiables par l''administrateur';
COMMENT ON SCHEMA pat IS 'Patrimoine géré : propriétaires, bâtiments, lots, mandats';
COMMENT ON SCHEMA loc IS 'Gestion locative : occupants, baux, échéances, encaissements';
COMMENT ON SCHEMA tra IS 'Interventions, travaux et chantiers';
COMMENT ON SCHEMA web IS 'Annonces publiées sur le site et demandes des visiteurs';
COMMENT ON SCHEMA app IS 'Comptes de la plateforme, droits et journal des actions';
COMMENT ON SCHEMA tdb IS 'Vues de restitution des tableaux de bord';

-- -----------------------------------------------------------------------------
-- Référentiel administratif
-- -----------------------------------------------------------------------------
-- « centre » sert de point de repli pour cadrer la carte tant que les limites
-- officielles (geom) ne sont pas chargées : elles viendront d'un fichier GeoJSON
-- du District autonome d'Abidjan (voir database/README.md).
CREATE TABLE adm.commune (
    id_commune  integer PRIMARY KEY,
    code        varchar(20)  NOT NULL UNIQUE,
    libelle     varchar(120) NOT NULL,
    district    varchar(80)  NOT NULL DEFAULT 'Abidjan',
    ordre       smallint     NOT NULL DEFAULT 0,
    actif       boolean      NOT NULL DEFAULT true,
    centre      geometry(Point, 4326) NOT NULL,
    geom        geometry(MultiPolygon, 4326)
);
CREATE INDEX idx_commune_centre ON adm.commune USING gist (centre);
CREATE INDEX idx_commune_geom   ON adm.commune USING gist (geom);

COMMENT ON TABLE  adm.commune        IS 'Communes du District autonome d''Abidjan et environs où FACT Immobilier gère des biens';
COMMENT ON COLUMN adm.commune.centre IS 'Point de centrage approximatif de la commune, en attendant ses limites officielles';
COMMENT ON COLUMN adm.commune.geom   IS 'Limites officielles (à charger depuis un GeoJSON) ; nul tant qu''elles ne sont pas disponibles';

CREATE TABLE adm.quartier (
    id_quartier  integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_commune   integer      NOT NULL REFERENCES adm.commune,
    code         varchar(30)  NOT NULL UNIQUE,
    libelle      varchar(120) NOT NULL,
    ordre        smallint     NOT NULL DEFAULT 0,
    actif        boolean      NOT NULL DEFAULT true,
    centre       geometry(Point, 4326),
    geom         geometry(MultiPolygon, 4326),
    UNIQUE (id_commune, libelle)
);
CREATE INDEX idx_quartier_commune ON adm.quartier (id_commune);
CREATE INDEX idx_quartier_geom    ON adm.quartier USING gist (geom);

COMMENT ON TABLE adm.quartier IS 'Quartiers, par commune — sert à localiser chaque bâtiment et à filtrer la recherche du site';

-- -----------------------------------------------------------------------------
-- Nomenclatures : une ligne par choix de formulaire (§ 7.2 des spécifications)
-- -----------------------------------------------------------------------------
CREATE TABLE ref.valeur (
    id       integer GENERATED ALWAYS AS IDENTITY UNIQUE,
    domaine  varchar(40)  NOT NULL,
    code     varchar(40)  NOT NULL,
    libelle  varchar(160) NOT NULL,
    ordre    smallint     NOT NULL DEFAULT 0,
    actif    boolean      NOT NULL DEFAULT true,
    PRIMARY KEY (domaine, code)
);
COMMENT ON TABLE ref.valeur IS 'Listes de valeurs des formulaires, une ligne par choix ; modifiables par l''administrateur';

INSERT INTO ref.valeur (domaine, code, libelle, ordre) VALUES
    -- Opération commerciale (site : onglets « Louer » / « Acheter »)
    ('OPERATION', 'LOCATION', 'À louer', 1),
    ('OPERATION', 'VENTE',    'À vendre', 2),

    -- Type de bâtiment porté sur la carte
    ('TYPE_BATIMENT', 'IMMEUBLE',         'Immeuble', 1),
    ('TYPE_BATIMENT', 'RESIDENCE',        'Résidence / cour commune', 2),
    ('TYPE_BATIMENT', 'VILLA',            'Villa', 3),
    ('TYPE_BATIMENT', 'DUPLEX',           'Duplex', 4),
    ('TYPE_BATIMENT', 'LOCAL_COMMERCIAL', 'Local commercial', 5),
    ('TYPE_BATIMENT', 'BUREAUX',          'Immeuble de bureaux', 6),
    ('TYPE_BATIMENT', 'TERRAIN',          'Terrain nu', 7),
    ('TYPE_BATIMENT', 'CHANTIER',         'Chantier de construction', 8),

    -- Type de lot (§ 3.2 : critère « type de bien » du site)
    ('TYPE_LOT', 'STUDIO',   'Studio', 1),
    ('TYPE_LOT', 'F2',       'Appartement F2', 2),
    ('TYPE_LOT', 'F3',       'Appartement F3', 3),
    ('TYPE_LOT', 'F4',       'Appartement F4', 4),
    ('TYPE_LOT', 'F5_PLUS',  'Appartement F5 et plus', 5),
    ('TYPE_LOT', 'VILLA',    'Villa', 6),
    ('TYPE_LOT', 'DUPLEX',   'Duplex', 7),
    ('TYPE_LOT', 'BUREAU',   'Bureau', 8),
    ('TYPE_LOT', 'MAGASIN',  'Magasin / boutique', 9),
    ('TYPE_LOT', 'ENTREPOT', 'Entrepôt', 10),
    ('TYPE_LOT', 'TERRAIN',  'Terrain', 11),

    -- Statuts d'un lot (§ 4.2 : cycle de vie)
    ('STATUT_LOT', 'VACANT',       'Vacant', 1),
    ('STATUT_LOT', 'A_LOUER',      'À louer', 2),
    ('STATUT_LOT', 'A_VENDRE',     'À vendre', 3),
    ('STATUT_LOT', 'RESERVE',      'Réservé', 4),
    ('STATUT_LOT', 'OCCUPE',       'Occupé', 5),
    ('STATUT_LOT', 'EN_PREAVIS',   'En préavis', 6),
    ('STATUT_LOT', 'EN_TRAVAUX',   'En travaux', 7),
    ('STATUT_LOT', 'HORS_GESTION', 'Hors gestion', 8),

    -- Équipements (cases à cocher de la recherche, § 3.2)
    ('EQUIPEMENT', 'MEUBLE',        'Meublé', 1),
    ('EQUIPEMENT', 'PARKING',       'Parking', 2),
    ('EQUIPEMENT', 'GARDIENNAGE',   'Gardiennage', 3),
    ('EQUIPEMENT', 'GROUPE',        'Groupe électrogène', 4),
    ('EQUIPEMENT', 'PISCINE',       'Piscine', 5),
    ('EQUIPEMENT', 'ASCENSEUR',     'Ascenseur', 6),
    ('EQUIPEMENT', 'CLIMATISATION', 'Climatisation', 7),
    ('EQUIPEMENT', 'EAU_CHAUDE',    'Eau chaude', 8),
    ('EQUIPEMENT', 'BALCON',        'Balcon / terrasse', 9),
    ('EQUIPEMENT', 'COUR',          'Cour', 10),

    -- Mandat confié par le propriétaire
    ('TYPE_MANDAT', 'GESTION_LOCATIVE', 'Gestion locative', 1),
    ('TYPE_MANDAT', 'MISE_EN_LOCATION', 'Mise en location seule', 2),
    ('TYPE_MANDAT', 'VENTE',            'Mandat de vente', 3),
    ('TYPE_MANDAT', 'SYNDIC',           'Administration de l''immeuble', 4),

    -- Occupants et baux
    ('TYPE_PERSONNE', 'PHYSIQUE', 'Personne physique', 1),
    ('TYPE_PERSONNE', 'MORALE',   'Personne morale (société, association)', 2),
    ('SEXE', 'F', 'Féminin', 1),
    ('SEXE', 'M', 'Masculin', 2),
    ('STATUT_BAIL', 'ACTIF',   'Actif', 1),
    ('STATUT_BAIL', 'PREAVIS', 'En préavis', 2),
    ('STATUT_BAIL', 'RESILIE', 'Résilié', 3),
    ('STATUT_BAIL', 'ECHU',    'Échu', 4),

    -- Encaissements (§ 4.4)
    ('MODE_PAIEMENT', 'ESPECES',      'Espèces', 1),
    ('MODE_PAIEMENT', 'MOBILE_MONEY', 'Mobile money', 2),
    ('MODE_PAIEMENT', 'CHEQUE',       'Chèque', 3),
    ('MODE_PAIEMENT', 'VIREMENT',     'Virement bancaire', 4),
    ('STATUT_PAIEMENT', 'A_JOUR',  'À jour', 1),
    ('STATUT_PAIEMENT', 'PARTIEL', 'Paiement partiel', 2),
    ('STATUT_PAIEMENT', 'IMPAYE',  'Impayé', 3),

    -- Interventions (§ 4.5)
    ('CATEGORIE_INTERVENTION', 'PLOMBERIE',     'Plomberie', 1),
    ('CATEGORIE_INTERVENTION', 'ELECTRICITE',   'Électricité', 2),
    ('CATEGORIE_INTERVENTION', 'PEINTURE',      'Peinture', 3),
    ('CATEGORIE_INTERVENTION', 'MACONNERIE',    'Maçonnerie', 4),
    ('CATEGORIE_INTERVENTION', 'MENUISERIE',    'Menuiserie', 5),
    ('CATEGORIE_INTERVENTION', 'ETANCHEITE',    'Étanchéité / toiture', 6),
    ('CATEGORIE_INTERVENTION', 'CLIMATISATION', 'Climatisation', 7),
    ('CATEGORIE_INTERVENTION', 'ASSAINISSEMENT','Assainissement', 8),
    ('CATEGORIE_INTERVENTION', 'NETTOYAGE',     'Nettoyage / enlèvement', 9),
    ('CATEGORIE_INTERVENTION', 'AUTRE',         'Autre', 99),
    ('URGENCE', 'BASSE',    'Peut attendre', 1),
    ('URGENCE', 'NORMALE',  'Normale', 2),
    ('URGENCE', 'HAUTE',    'Urgente', 3),
    ('URGENCE', 'CRITIQUE', 'Critique (sécurité, dégât d''eau)', 4),
    ('STATUT_INTERVENTION', 'SIGNALEE', 'Signalée', 1),
    ('STATUT_INTERVENTION', 'AFFECTEE', 'Affectée', 2),
    ('STATUT_INTERVENTION', 'EN_COURS', 'En cours', 3),
    ('STATUT_INTERVENTION', 'VALIDEE',  'Devis validé', 4),
    ('STATUT_INTERVENTION', 'TERMINEE', 'Terminée', 5),
    ('STATUT_INTERVENTION', 'CLOTUREE', 'Clôturée', 6),
    ('STATUT_INTERVENTION', 'ANNULEE',  'Annulée', 7),

    -- Demandes venues du site (§ 3.8)
    ('TYPE_DEMANDE', 'VISITE',     'Demande de visite', 1),
    ('TYPE_DEMANDE', 'MANDAT',     'Confier un bien en gestion', 2),
    ('TYPE_DEMANDE', 'DEVIS',      'Demande de devis', 3),
    ('TYPE_DEMANDE', 'ESTIMATION', 'Demande d''estimation', 4),
    ('TYPE_DEMANDE', 'CONTACT',    'Message de contact', 5),
    ('STATUT_DEMANDE', 'NOUVELLE',        'Nouvelle', 1),
    ('STATUT_DEMANDE', 'PRISE_EN_CHARGE', 'Prise en charge', 2),
    ('STATUT_DEMANDE', 'RENDEZ_VOUS',     'Rendez-vous fixé', 3),
    ('STATUT_DEMANDE', 'CONCLUE',         'Conclue', 4),
    ('STATUT_DEMANDE', 'SANS_SUITE',      'Sans suite', 5),

    -- Métiers de l'agence (pages du site et demandes de devis)
    ('METIER', 'GESTION',      'Gestion immobilière', 1),
    ('METIER', 'CONSTRUCTION', 'Construction', 2),
    ('METIER', 'TRAVAUX',      'Travaux et assainissement', 3),
    ('METIER', 'TRANSACTION',  'Transactions immobilières', 4),

    -- Origine d'une fiche : d'où vient la donnée
    ('ORIGINE', 'TERRAIN',       'Collecte terrain (PWA)', 1),
    ('ORIGINE', 'IMPORT',        'Import d''un fichier de l''agence', 2),
    ('ORIGINE', 'SAISIE',        'Saisie au bureau', 3),
    ('ORIGINE', 'SITE',          'Formulaire du site web', 4),
    ('ORIGINE', 'DEMONSTRATION', 'Jeu de démonstration', 9);

-- -----------------------------------------------------------------------------
-- Paramètres réglables (seuils et délais des § 4.1 et § 4.4)
-- -----------------------------------------------------------------------------
CREATE TABLE ref.reglage (
    cle      varchar(60)  PRIMARY KEY,
    valeur   varchar(200) NOT NULL,
    libelle  varchar(200) NOT NULL,
    unite    varchar(30),
    maj_le   timestamptz  NOT NULL DEFAULT now(),
    maj_par  varchar(80)
);
COMMENT ON TABLE ref.reglage IS 'Seuils et délais paramétrables par l''administrateur (§ 4.1, § 4.4 des spécifications)';

INSERT INTO ref.reglage (cle, valeur, libelle, unite) VALUES
    ('loyer.jours_de_grace',                 '5',    'Jours après l''exigibilité avant qu''un loyer soit dit en retard', 'jours'),
    ('loyer.retard_leger_mois',              '1',    'Mois de retard au-delà desquels le bâtiment passe en orange', 'mois'),
    ('loyer.retard_grave_mois',              '2',    'Mois de retard au-delà desquels le bâtiment passe en rouge', 'mois'),
    ('bail.alerte_echeance_jours',           '60',   'Premier rappel avant la fin d''un bail', 'jours'),
    ('bail.relance_echeance_jours',          '30',   'Second rappel avant la fin d''un bail', 'jours'),
    ('lot.vacance_alerte_jours',             '30',   'Jours de vacance au-delà desquels un lot est signalé', 'jours'),
    ('mandat.alerte_renouvellement_jours',   '90',   'Délai d''alerte avant l''échéance d''un mandat', 'jours'),
    ('annonce.photos_minimum',               '3',    'Nombre de photos exigées pour publier une annonce', 'photos'),
    ('demande.delai_prise_en_charge_heures', '24',   'Heures ouvrées au-delà desquelles une demande « Nouvelle » est signalée', 'heures'),
    ('agence.annee_creation',                '2013', 'Année de création de l''agence', 'année'),
    ('agence.annee_demarrage',               '2014', 'Année de démarrage de l''activité', 'année');

-- -----------------------------------------------------------------------------
-- Plateforme : comptes et journal
-- -----------------------------------------------------------------------------
-- Les profils du § 2.1 sont portés par « profil » ; les droits fins du § 2.2 par
-- le tableau « droits ». Aucun mot de passe n'est conservé : seule son empreinte BCrypt.
CREATE TABLE app.compte (
    id                 integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    identifiant        varchar(40)  NOT NULL UNIQUE,
    nom                varchar(120) NOT NULL,
    fonction           varchar(120),
    email              varchar(160),
    telephone          varchar(30),
    profil             varchar(30)  NOT NULL,
    droits             text[]       NOT NULL DEFAULT '{}',
    mot_de_passe       varchar(100) NOT NULL,
    actif              boolean      NOT NULL DEFAULT true,
    doit_changer       boolean      NOT NULL DEFAULT true,
    version_session    integer      NOT NULL DEFAULT 0,
    derniere_connexion timestamptz,
    cree_le            timestamptz  NOT NULL DEFAULT now(),
    maj_le             timestamptz,
    CONSTRAINT chk_compte_identifiant CHECK (identifiant ~ '^[a-z0-9._-]{3,40}$'),
    CONSTRAINT chk_compte_email CHECK (email IS NULL OR email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[a-z]{2,}$'),
    CONSTRAINT chk_compte_profil CHECK (profil IN
        ('PROPRIETAIRE', 'AGENT', 'GESTIONNAIRE', 'TRAVAUX', 'EDITEUR', 'DIRECTION', 'ADMIN'))
);
CREATE INDEX idx_compte_profil ON app.compte (profil) WHERE actif;

COMMENT ON TABLE  app.compte        IS 'Comptes de la plateforme (profils du § 2.1 des spécifications)';
COMMENT ON COLUMN app.compte.profil IS 'PROPRIETAIRE, AGENT, GESTIONNAIRE, TRAVAUX, EDITEUR, DIRECTION, ADMIN';
COMMENT ON COLUMN app.compte.droits IS 'Droits fins accordés en plus de ceux du profil (matrice du § 2.2)';

-- Journal des actions : « aucune suppression définitive » et traçabilité (§ 8)
CREATE TABLE app.journal (
    id         bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    auteur     varchar(80)  NOT NULL,
    objet      varchar(40)  NOT NULL,
    cle        varchar(80),
    action     varchar(200) NOT NULL,
    avant      jsonb,
    apres      jsonb,
    adresse_ip varchar(60),
    le         timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_journal_le    ON app.journal (le DESC);
CREATE INDEX idx_journal_objet ON app.journal (objet, cle);

COMMENT ON TABLE  app.journal       IS 'Qui a modifié quoi et quand (SIG-22) : ancienne et nouvelle valeur en JSON';
COMMENT ON COLUMN app.journal.objet IS 'Nature de l''objet touché : COMPTE, BATIMENT, LOT, BAIL, ENCAISSEMENT, ANNONCE…';
