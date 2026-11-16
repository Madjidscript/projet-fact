package com.eburtis.fact.repository;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.eburtis.fact.domain.Commune;
import com.eburtis.fact.domain.Quartier;
import com.eburtis.fact.domain.Valeur;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Le référentiel : communes et quartiers (adm), listes de valeurs et réglages (ref).
 * Ces données changent rarement : elles sont lues telles quelles et mises en cache un
 * niveau au-dessus (ReferentielFacade).
 */
@Repository
public class ReferentielRepository {

    private final JdbcTemplate jdbc;

    public ReferentielRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Les communes actives, chacune avec ses quartiers. */
    public List<Commune> communes() {
        Map<Integer, List<Quartier>> parCommune = quartiers().stream().collect(Collectors.groupingBy(Quartier::idCommune));
        return jdbc.query("""
                SELECT id_commune, code, libelle, district,
                       ST_Y(centre) AS lat, ST_X(centre) AS lon, geom IS NOT NULL AS limites
                FROM adm.commune WHERE actif ORDER BY ordre, libelle""", (rs, i) -> {
            int id = rs.getInt("id_commune");
            return new Commune(id, rs.getString("code"), rs.getString("libelle"), rs.getString("district"),
                    rs.getDouble("lat"), rs.getDouble("lon"), rs.getBoolean("limites"),
                    parCommune.getOrDefault(id, List.of()));
        });
    }

    public List<Quartier> quartiers() {
        return jdbc.query("""
                SELECT id_quartier, id_commune, code, libelle
                FROM adm.quartier WHERE actif ORDER BY id_commune, ordre, libelle""",
                (rs, i) -> new Quartier(rs.getInt("id_quartier"), rs.getInt("id_commune"), rs.getString("code"),
                        rs.getString("libelle")));
    }

    /** Toutes les listes de valeurs, à charge de l'appelant de les regrouper par domaine. */
    public List<Valeur> valeurs() {
        return jdbc.query("""
                SELECT domaine, code, libelle, ordre FROM ref.valeur WHERE actif
                ORDER BY domaine, ordre, libelle""",
                (rs, i) -> new Valeur(rs.getString("domaine"), rs.getString("code"), rs.getString("libelle"),
                        rs.getInt("ordre")));
    }

    /** Les seuils et délais réglables, par leur clé. */
    public Map<String, String> reglages() {
        return jdbc.query("SELECT cle, valeur FROM ref.reglage ORDER BY cle",
                        (rs, i) -> Map.entry(rs.getString("cle"), rs.getString("valeur")))
                .stream().collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b,
                        java.util.LinkedHashMap::new));
    }
}
