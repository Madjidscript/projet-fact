-- =============================================================================
-- L'assistant IA : le journal des tours de boucle, et les validations humaines.
-- =============================================================================

-- Un enregistrement par appel au modèle : sert au budget de tokens du jour et au débogage
-- (« pourquoi l'assistant a-t-il fait ça ? » : on relit ses tours un par un).
CREATE TABLE app.assistant_tour (
    id          bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_compte   integer      NOT NULL REFERENCES app.compte ON DELETE CASCADE,
    tache       uuid         NOT NULL,                -- les tours d'une même question partagent ce numéro
    tour        integer      NOT NULL,
    outils      varchar(400),                         -- les outils demandés à ce tour, séparés par des virgules
    duree_ms    integer      NOT NULL,
    tokens_in   integer      NOT NULL DEFAULT 0,
    tokens_out  integer      NOT NULL DEFAULT 0,
    erreur      varchar(400),
    cree_le     timestamptz  NOT NULL DEFAULT now()
);

CREATE INDEX idx_assistant_tour_compte_jour ON app.assistant_tour (id_compte, cree_le);

COMMENT ON TABLE app.assistant_tour IS 'Chaque appel de l''assistant IA au modèle : outils demandés, durée, tokens.';

-- Une action proposée par l'assistant qui modifie des données : rien n'est fait avant le clic « Valider ».
CREATE TABLE app.assistant_validation (
    id           bigint       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_compte    integer      NOT NULL REFERENCES app.compte ON DELETE CASCADE,
    outil        varchar(60)  NOT NULL,
    parametres   text         NOT NULL,               -- les paramètres EXACTS qui seront exécutés, en JSON
    empreinte    char(64)     NOT NULL,               -- SHA-256 des paramètres : ce qui part est ce qui a été montré
    resume       text         NOT NULL,               -- ce que la carte affiche à la personne
    statut       varchar(12)  NOT NULL DEFAULT 'EN_ATTENTE',
    expire_le    timestamptz  NOT NULL,
    resultat     text,
    cree_le      timestamptz  NOT NULL DEFAULT now(),
    traite_le    timestamptz,
    CONSTRAINT chk_assistant_validation_statut CHECK (statut IN ('EN_ATTENTE', 'VALIDEE', 'ANNULEE', 'EXPIREE'))
);

CREATE INDEX idx_assistant_validation_compte ON app.assistant_validation (id_compte, statut);

COMMENT ON TABLE app.assistant_validation IS 'Actions proposées par l''assistant IA, en attente du clic de la personne (10 minutes).';
