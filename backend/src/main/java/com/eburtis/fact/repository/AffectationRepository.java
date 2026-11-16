package com.eburtis.fact.repository;

import java.util.List;
import java.util.UUID;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Qui gère quoi (§ 4.3) : portefeuilles des gestionnaires, réaffectations et leur historique. */
@Repository
public class AffectationRepository {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public AffectationRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /**
     * Un portefeuille par gestionnaire actif, plus « Sans gestionnaire » (id nul) : bâtiments, lots,
     * occupation, impayés en retard, interventions ouvertes, et les bâtiments qu'il supplée.
     */
    public JsonNode portefeuilles() {
        return json.lire(jdbc.queryForObject("""
                WITH g AS (
                    SELECT c.id, c.nom, c.fonction, c.email, c.telephone FROM app.compte c WHERE c.actif AND c.profil = 'GESTIONNAIRE'
                    UNION ALL SELECT NULL, 'Sans gestionnaire', NULL, NULL, NULL)
                SELECT coalesce(json_agg(t ORDER BY t.id IS NULL, t.nom), '[]')::text FROM (
                    SELECT g.id, g.nom, g.fonction, g.email, g.telephone,
                           count(v.id) AS batiments,
                           coalesce(sum(v.lots), 0) AS lots,
                           coalesce(sum(v.lots_occupes), 0) AS occupes,
                           round(100.0 * sum(v.lots_occupes) / nullif(sum(v.lots), 0), 1) AS taux,
                           coalesce(sum(v.impaye), 0) AS impaye,
                           coalesce(sum(v.interventions_ouvertes), 0) AS interventions,
                           (SELECT count(*) FROM pat.batiment s WHERE s.archive_le IS NULL AND g.id IS NOT NULL AND s.id_suppleant = g.id) AS supplees
                    FROM g LEFT JOIN pat.v_batiment v ON v.archive_le IS NULL AND v.id_gestionnaire IS NOT DISTINCT FROM g.id
                    GROUP BY g.id, g.nom, g.fonction, g.email, g.telephone) t
                WHERE t.id IS NOT NULL OR t.batiments > 0""", String.class));
    }

    /** Les bâtiments à réaffecter, avec leur position pour la carte de sélection. */
    public JsonNode batiments() {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.nom), '[]')::text FROM (
                    SELECT v.id, v.reference, v.nom, v.commune, v.quartier, v.latitude, v.longitude, v.lots,
                           v.lots_occupes AS occupes, v.impaye, v.id_gestionnaire AS "idGestionnaire", v.gestionnaire,
                           v.id_suppleant AS "idSuppleant", v.suppleant, v.proprietaire
                    FROM pat.v_batiment v WHERE v.archive_le IS NULL) t""", String.class));
    }

    /** Pose l'identifiant d'opération et le motif lus par le déclencheur d'historique, pour cette transaction. */
    public void preparerOperation(UUID operation, String motif) {
        jdbc.queryForObject("SELECT set_config('fact.operation', ?, true) || set_config('fact.motif', ?, true)", String.class,
                operation.toString(), motif == null ? "" : motif);
    }

    /** Change le gestionnaire (et le suppléant si {@code suppleant} est vrai) ; rend le nombre de bâtiments changés. */
    public int reaffecter(List<Integer> batiments, Integer gestionnaire, boolean changerSuppleant, Integer suppleant, String auteur) {
        String ids = "{" + String.join(",", batiments.stream().map(String::valueOf).toList()) + "}";
        if (changerSuppleant) {
            return jdbc.update("""
                    UPDATE pat.batiment SET id_gestionnaire = ?, id_suppleant = ?, maj_le = now(), maj_par = ?
                    WHERE id = ANY (?::int[]) AND archive_le IS NULL
                      AND (id_gestionnaire IS DISTINCT FROM ? OR id_suppleant IS DISTINCT FROM ?)""",
                    gestionnaire, suppleant, auteur, ids, gestionnaire, suppleant);
        }
        // Le suppléant est gardé, sauf s'il devient le gestionnaire lui-même.
        return jdbc.update("""
                UPDATE pat.batiment SET id_gestionnaire = ?, id_suppleant = CASE WHEN id_suppleant = ? OR ?::int IS NULL THEN NULL ELSE id_suppleant END,
                    maj_le = now(), maj_par = ?
                WHERE id = ANY (?::int[]) AND archive_le IS NULL AND id_gestionnaire IS DISTINCT FROM ?""",
                gestionnaire, gestionnaire, gestionnaire, auteur, ids, gestionnaire);
    }

    /** Les bâtiments inconnus ou archivés de la liste. */
    public int inconnus(List<Integer> batiments) {
        Integer n = jdbc.queryForObject("SELECT ? - count(*) FROM pat.batiment WHERE id = ANY (?::int[]) AND archive_le IS NULL",
                Integer.class, batiments.size(), "{" + String.join(",", batiments.stream().map(String::valueOf).toList()) + "}");
        return n == null ? 0 : n;
    }

    /** L'historique des réaffectations, regroupées par opération ; filtré sur un bâtiment si demandé. */
    public JsonNode historique(Integer idBatiment, int limite) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.le DESC), '[]')::text FROM (
                    SELECT a.id, a.id_batiment AS "idBatiment", b.nom AS batiment, a.operation, a.motif, a.le, a.par,
                           ag.nom AS "ancienGestionnaire", ng.nom AS "nouveauGestionnaire",
                           asp.nom AS "ancienSuppleant", nsp.nom AS "nouveauSuppleant"
                    FROM pat.affectation a
                    JOIN pat.batiment b ON b.id = a.id_batiment
                    LEFT JOIN app.compte ag ON ag.id = a.ancien_gestionnaire
                    LEFT JOIN app.compte ng ON ng.id = a.nouveau_gestionnaire
                    LEFT JOIN app.compte asp ON asp.id = a.ancien_suppleant
                    LEFT JOIN app.compte nsp ON nsp.id = a.nouveau_suppleant
                    WHERE ?::int IS NULL OR a.id_batiment = ?::int
                    ORDER BY a.le DESC LIMIT ?) t""", String.class, idBatiment, idBatiment, limite));
    }

    /** Prévient le nouveau gestionnaire (ou suppléant) : une alerte par opération. */
    public void prevenir(int idCompte, UUID operation, int nombre, String role, String auteur, String motif) {
        jdbc.update("""
                INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien)
                VALUES (?, 'AFFECTATION', ?, 'INFO', ?, ?, '/patrimoine')
                ON CONFLICT DO NOTHING""",
                idCompte, operation + "-" + role,
                nombre + (nombre > 1 ? " bâtiments vous sont confiés" : " bâtiment vous est confié") + ("SUPPLEANT".equals(role) ? " en suppléance" : ""),
                "Réaffectation faite par " + auteur + (motif == null ? "." : " : " + motif + "."));
    }
}
