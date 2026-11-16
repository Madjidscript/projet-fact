-- =============================================================================
-- Étape 8 : application de terrain (§ 4.6). Photos des bâtiments ; recensements reçus,
-- identifiés par une clé tirée sur le téléphone pour qu'un renvoi ne crée pas de doublon.
-- =============================================================================

CREATE TABLE pat.batiment_photo (
    id          integer GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_batiment integer      NOT NULL REFERENCES pat.batiment,
    chemin      varchar(300) NOT NULL,
    largeur     integer,
    hauteur     integer,
    ordre       smallint     NOT NULL DEFAULT 0,
    prise_le    timestamptz,                         -- l'heure de la prise de vue sur le téléphone
    cree_le     timestamptz  NOT NULL DEFAULT now(),
    cree_par    varchar(80)
);
CREATE INDEX idx_batiment_photo ON pat.batiment_photo (id_batiment, ordre);
COMMENT ON TABLE pat.batiment_photo IS 'Photos d''un bâtiment, prises sur le terrain ou ajoutées au bureau';

CREATE TABLE pat.recensement (
    cle         uuid         PRIMARY KEY,            -- tirée sur le téléphone à la saisie
    id_batiment integer      NOT NULL REFERENCES pat.batiment,
    saisi_le    timestamptz,                         -- l'heure de la saisie, peut-être hors connexion
    recu_le     timestamptz  NOT NULL DEFAULT now(),
    par         varchar(80)
);
COMMENT ON TABLE pat.recensement IS 'Recensements reçus du terrain : une clé, un bâtiment, même si l''envoi est rejoué';
