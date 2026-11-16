package com.eburtis.fact.repository;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les alertes adressées aux comptes (table app.alerte, § 4.7). */
@Repository
public class AlerteRepository {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public AlerteRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Crée les alertes dues et marque résolues celles qui ne le sont plus ; rend le nombre créé. */
    public int generer() {
        Integer n = jdbc.queryForObject("SELECT app.generer_alertes() + app.generer_alertes_web() + app.generer_alertes_proprietaires()", Integer.class);
        return n == null ? 0 : n;
    }

    /** Les alertes d'un compte : celles qui demandent encore une action d'abord, puis les plus récentes. */
    public JsonNode lister(int idCompte, int limite) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t), '[]')::text FROM (
                    SELECT a.id, a.type, a.gravite, a.titre, a.message, a.lien, a.id_batiment AS "idBatiment",
                           a.cree_le AS "creeLe", a.lue_le AS "lueLe", a.resolue_le AS "resolueLe"
                    FROM app.alerte a
                    WHERE a.id_compte = ? AND (a.resolue_le IS NULL OR a.resolue_le > now() - interval '7 days')
                    ORDER BY (a.resolue_le IS NULL) DESC, (a.lue_le IS NULL) DESC,
                             CASE a.gravite WHEN 'CRITIQUE' THEN 0 WHEN 'ATTENTION' THEN 1 ELSE 2 END, a.cree_le DESC
                    LIMIT ?) t""", String.class, idCompte, limite));
    }

    /** Ce que la cloche affiche : alertes non lues et encore d'actualité, dont les critiques. */
    public JsonNode compte(int idCompte) {
        return json.lire(jdbc.queryForObject("""
                SELECT json_build_object(
                    'nonLues', count(*) FILTER (WHERE lue_le IS NULL),
                    'critiques', count(*) FILTER (WHERE lue_le IS NULL AND gravite = 'CRITIQUE'),
                    'derniere', max(cree_le))::text
                FROM app.alerte WHERE id_compte = ? AND resolue_le IS NULL""", String.class, idCompte));
    }

    public int marquerLue(long id, int idCompte) {
        return jdbc.update("UPDATE app.alerte SET lue_le = coalesce(lue_le, now()) WHERE id = ? AND id_compte = ?", id, idCompte);
    }

    public int toutMarquerLu(int idCompte) {
        return jdbc.update("UPDATE app.alerte SET lue_le = now() WHERE id_compte = ? AND lue_le IS NULL", idCompte);
    }

    /**
     * La réponse d'un propriétaire à un devis : le responsable travaux (à défaut la Direction) et le
     * gestionnaire du bâtiment sont prévenus.
     */
    public void prevenirReponseDevis(int idIntervention, String decision, String titre, String message, int idBatiment) {
        jdbc.update("""
                INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien, id_batiment)
                SELECT d, 'ACCORD_PROPRIETAIRE', ? || '-' || ?, CASE WHEN ? = 'REFUS' THEN 'ATTENTION' ELSE 'INFO' END, ?, ?,
                       '/travaux?intervention=' || ?, ?
                FROM (SELECT r AS d FROM app.responsables_travaux() r
                      UNION SELECT id_gestionnaire FROM pat.batiment WHERE id = ? AND id_gestionnaire IS NOT NULL) x
                ON CONFLICT DO NOTHING""",
                String.valueOf(idIntervention), decision, decision, titre, message, idIntervention, idBatiment, idBatiment);
    }
}
