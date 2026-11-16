-- =============================================================================
-- Étape 7 : qui gère quoi (§ 4.3). Toute réaffectation est datée et conservée.
-- =============================================================================

CREATE TABLE pat.affectation (
    id                   bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    id_batiment          integer      NOT NULL REFERENCES pat.batiment,
    ancien_gestionnaire  integer      REFERENCES app.compte,
    nouveau_gestionnaire integer      REFERENCES app.compte,
    ancien_suppleant     integer      REFERENCES app.compte,
    nouveau_suppleant    integer      REFERENCES app.compte,
    operation            uuid,                      -- une réaffectation en masse : un seul identifiant
    motif                varchar(250),
    le                   timestamptz  NOT NULL DEFAULT now(),
    par                  varchar(80)
);
CREATE INDEX idx_affectation_batiment ON pat.affectation (id_batiment, le DESC);
CREATE INDEX idx_affectation_le ON pat.affectation (le DESC);
COMMENT ON TABLE pat.affectation IS 'Historique des changements de gestionnaire et de suppléant (§ 4.3)';

/**
 * Chaque changement de gestionnaire ou de suppléant d'un bâtiment est consigné, quel que soit
 * l'écran qui l'a fait (fiche du bâtiment, réaffectation en masse, import). La réaffectation en
 * masse pose son identifiant d'opération et son motif dans la transaction (set_config).
 */
CREATE FUNCTION pat.consigner_affectation() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.id_gestionnaire IS DISTINCT FROM OLD.id_gestionnaire OR NEW.id_suppleant IS DISTINCT FROM OLD.id_suppleant THEN
        INSERT INTO pat.affectation (id_batiment, ancien_gestionnaire, nouveau_gestionnaire, ancien_suppleant, nouveau_suppleant,
                                     operation, motif, par)
        VALUES (NEW.id, OLD.id_gestionnaire, NEW.id_gestionnaire, OLD.id_suppleant, NEW.id_suppleant,
                nullif(current_setting('fact.operation', true), '')::uuid,
                nullif(current_setting('fact.motif', true), ''),
                NEW.maj_par);
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_batiment_affectation AFTER UPDATE OF id_gestionnaire, id_suppleant ON pat.batiment
    FOR EACH ROW EXECUTE FUNCTION pat.consigner_affectation();

-- Le nouveau gestionnaire est prévenu (§ 4.3)
ALTER TABLE app.alerte DROP CONSTRAINT chk_alerte_type;
ALTER TABLE app.alerte ADD CONSTRAINT chk_alerte_type CHECK (type IN ('BAIL_ECHEANCE', 'LOYER_RETARD', 'LOT_VACANT',
    'MANDAT_ECHEANCE', 'INTERVENTION_URGENTE', 'SANS_GESTIONNAIRE', 'DEMANDE_NOUVELLE', 'DEMANDE_EN_ATTENTE',
    'ANNONCE_A_VALIDER', 'AFFECTATION'));
