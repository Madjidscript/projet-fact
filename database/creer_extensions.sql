-- =============================================================================
-- FACT Immobilier — extensions de la base
-- À jouer en superutilisateur, connecté à la base « fact_immobilier » :
--   psql -U postgres -d fact_immobilier -f database/creer_extensions.sql
-- Les migrations Flyway les redemandent (CREATE EXTENSION IF NOT EXISTS), mais
-- le compte de l'application n'a pas toujours le droit de les créer.
-- =============================================================================

CREATE EXTENSION IF NOT EXISTS postgis;    -- géométries, index spatiaux
CREATE EXTENSION IF NOT EXISTS unaccent;   -- recherche sans accent

GRANT ALL ON SCHEMA public TO fact_immobilier;
