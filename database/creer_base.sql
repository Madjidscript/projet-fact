-- =============================================================================
-- FACT Immobilier — création de la base et de ses extensions
-- -----------------------------------------------------------------------------
-- À jouer une seule fois, avant le premier démarrage de l'API. Les tables, elles,
-- sont créées par les migrations Flyway au démarrage (backend/.../flyway/schema).
--
--   1) en superutilisateur, sur la base « postgres » :
--        psql -U postgres -f database/creer_base.sql
--   2) puis les extensions, sur la base « fact_immobilier » :
--        psql -U postgres -d fact_immobilier -f database/creer_extensions.sql
-- =============================================================================

CREATE USER fact_immobilier WITH PASSWORD 'fact_immobilier';

-- La base reprend l'encodage et la locale du serveur. Sous Windows, les noms de
-- locale s'écrivent « French_France.1252 » et non « fr-FR » : on ne les impose
-- pas ici, l'installation standard de PostgreSQL étant déjà en UTF-8.
CREATE DATABASE fact_immobilier OWNER fact_immobilier;

COMMENT ON DATABASE fact_immobilier IS
    'FACT Immobilier — patrimoine géoréférencé, gestion locative et site web (CDC-FACT-2026-01)';

-- Le mot de passe ci-dessus est celui du développement local.
-- En production : mot de passe propre, et FACT_DB_MOT_DE_PASSE dans l'environnement.
