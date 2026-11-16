package com.eburtis.fact.repository;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Ce que la base dit d'elle-même : dernière migration appliquée, version de PostGIS. */
@Repository
public class SanteRepository {

    private final JdbcTemplate jdbc;

    public SanteRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String derniereMigration() {
        return jdbc.queryForObject(
                "SELECT max(version::numeric)::text FROM flyway_schema_history WHERE success AND version IS NOT NULL",
                String.class);
    }

    public String versionPostgis() {
        return jdbc.queryForObject("SELECT postgis_lib_version()", String.class);
    }

    public Integer nombreCommunes() {
        return jdbc.queryForObject("SELECT count(*) FROM adm.commune WHERE actif", Integer.class);
    }

    public Integer nombreQuartiers() {
        return jdbc.queryForObject("SELECT count(*) FROM adm.quartier WHERE actif", Integer.class);
    }
}
