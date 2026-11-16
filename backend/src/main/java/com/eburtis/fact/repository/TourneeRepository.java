package com.eburtis.fact.repository;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les tournées des agents et leurs étapes (§ 4.6). */
@Repository
public class TourneeRepository {

    private static final String TOURNEE = """
            SELECT t.id, t.reference, t.id_agent AS "idAgent", c.nom AS agent, t.jour, t.statut, t.consigne,
                   t.compte_rendu AS "compteRendu", t.demarree_le AS "demarreeLe", t.terminee_le AS "termineeLe",
                   t.cree_le AS "creeLe", t.cree_par AS "creePar",
                   (SELECT count(*) FROM ter.etape e WHERE e.id_tournee = t.id) AS etapes,
                   (SELECT count(*) FROM ter.etape e WHERE e.id_tournee = t.id AND e.pointee_le IS NOT NULL) AS pointees
            FROM ter.tournee t JOIN app.compte c ON c.id = t.id_agent""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public TourneeRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<JsonNode> trouver(int id) {
        return jdbc.queryForList("SELECT row_to_json(x)::text FROM (" + TOURNEE + " WHERE t.id = ?) x", String.class, id)
                .stream().findFirst().map(json::lire);
    }

    public Optional<JsonNode> duJour(int idAgent, LocalDate jour) {
        return jdbc.queryForList("SELECT row_to_json(x)::text FROM (" + TOURNEE + " WHERE t.id_agent = ? AND t.jour = ?) x",
                String.class, idAgent, jour).stream().findFirst().map(json::lire);
    }

    /** Les tournées d'un jour ; d'un seul agent si {@code idAgent} est donné. */
    public JsonNode duJourPourTous(LocalDate jour, Integer idAgent) {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(x ORDER BY x.agent), '[]')::text FROM (" + TOURNEE
                + " WHERE t.jour = ? AND (?::int IS NULL OR t.id_agent = ?)) x", String.class, jour, idAgent, idAgent));
    }

    /** Les étapes, avec ce qu'il faut savoir de chaque bâtiment pour la visite. */
    public JsonNode etapes(int idTournee) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(x ORDER BY x.ordre, x.id), '[]')::text FROM (
                    SELECT e.id, e.ordre, e.motif, e.consigne, e.pointee_le AS "pointeeLe", e.latitude AS "latitudePointage",
                           e.longitude AS "longitudePointage", e.precision_gps AS "precisionGps", e.distance_m AS "distanceM",
                           e.resultat, e.compte_rendu AS "compteRendu",
                           v.id AS "idBatiment", v.reference, v.nom AS batiment, v.adresse, v.quartier, v.commune,
                           v.latitude, v.longitude, v.gestionnaire, v.lots, v.lots_occupes AS "lotsOccupes",
                           v.impaye, v.interventions_ouvertes AS "interventionsOuvertes"
                    FROM ter.etape e JOIN pat.v_batiment v ON v.id = e.id_batiment
                    WHERE e.id_tournee = ?) x""", String.class, idTournee));
    }

    public int creer(int idAgent, LocalDate jour, String consigne, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO ter.tournee (id_agent, jour, consigne, cree_par) VALUES (?, ?, ?, ?) RETURNING id""",
                Integer.class, idAgent, jour, consigne, auteur);
        return id == null ? 0 : id;
    }

    public void modifier(int id, String consigne, String auteur) {
        jdbc.update("UPDATE ter.tournee SET consigne = ?, maj_le = now(), maj_par = ? WHERE id = ?", consigne, auteur, id);
    }

    /** Retire les étapes qui ne sont plus prévues ; celles déjà pointées restent. */
    public void retirerEtapesSauf(int idTournee, List<Integer> batiments) {
        jdbc.update("DELETE FROM ter.etape WHERE id_tournee = ? AND pointee_le IS NULL AND NOT (id_batiment = ANY (?))",
                idTournee, batiments.toArray(Integer[]::new));
    }

    public void poserEtape(int idTournee, int idBatiment, int ordre, String motif, String consigne) {
        jdbc.update("""
                INSERT INTO ter.etape (id_tournee, id_batiment, ordre, motif, consigne) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (id_tournee, id_batiment) DO UPDATE SET ordre = EXCLUDED.ordre, motif = EXCLUDED.motif,
                    consigne = EXCLUDED.consigne""", idTournee, idBatiment, ordre, motif, consigne);
    }

    public Optional<JsonNode> etape(int idEtape) {
        return jdbc.queryForList("""
                SELECT row_to_json(x)::text FROM (
                    SELECT e.id, e.id_tournee AS "idTournee", e.id_batiment AS "idBatiment", e.pointee_le AS "pointeeLe"
                    FROM ter.etape e WHERE e.id = ?) x""", String.class, idEtape).stream().findFirst().map(json::lire);
    }

    /** Pointe une étape ; la distance au bâtiment est mesurée ici, sur le globe. Rend cette distance. */
    public Integer pointer(int idEtape, OffsetDateTime le, Double latitude, Double longitude, Double precision, String resultat,
                           String compteRendu) {
        jdbc.update("""
                UPDATE ter.etape e SET pointee_le = coalesce(e.pointee_le, ?), latitude = coalesce(?, e.latitude),
                       longitude = coalesce(?, e.longitude), precision_gps = coalesce(?, e.precision_gps),
                       resultat = ?, compte_rendu = ?,
                       distance_m = CASE WHEN ?::float8 IS NULL THEN e.distance_m ELSE round(ST_Distance(b.geom::geography,
                           ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326)::geography))::int END
                FROM pat.batiment b WHERE b.id = e.id_batiment AND e.id = ?""",
                le, latitude, longitude, precision == null ? null : precision.floatValue(), resultat, compteRendu,
                latitude, longitude, latitude, idEtape);
        return jdbc.queryForObject("SELECT distance_m FROM ter.etape WHERE id = ?", Integer.class, idEtape);
    }

    public void demarrer(int idTournee) {
        jdbc.update("UPDATE ter.tournee SET statut = 'EN_COURS', demarree_le = coalesce(demarree_le, now()) WHERE id = ? AND statut = 'PREVUE'",
                idTournee);
    }

    public int terminer(int idTournee, String compteRendu, String auteur) {
        return jdbc.update("""
                UPDATE ter.tournee SET statut = 'TERMINEE', compte_rendu = ?, terminee_le = now(),
                       demarree_le = coalesce(demarree_le, now()), maj_le = now(), maj_par = ?
                WHERE id = ? AND statut <> 'TERMINEE'""", compteRendu, auteur, idTournee);
    }

    /** Prévient les gestionnaires des bâtiments visités qu'un compte rendu les attend. */
    public void prevenirGestionnaires(int idTournee, int idAgent, String titre, String message) {
        jdbc.update("""
                INSERT INTO app.alerte (id_compte, type, cle, gravite, titre, message, lien)
                SELECT DISTINCT b.id_gestionnaire, 'TOURNEE', ?::text, 'INFO', ?, ?, '/tournees?tournee=' || ?
                FROM ter.etape e JOIN pat.batiment b ON b.id = e.id_batiment
                WHERE e.id_tournee = ? AND b.id_gestionnaire IS NOT NULL AND b.id_gestionnaire <> ?
                ON CONFLICT DO NOTHING""", String.valueOf(idTournee), titre, message, idTournee, idTournee, idAgent);
    }

    /** Les comptes à qui confier une tournée : agents de terrain et gestionnaires actifs. */
    public JsonNode agents() {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', id, 'nom', nom, 'profil', profil, 'identifiant', identifiant)
                       ORDER BY (profil = 'AGENT') DESC, nom), '[]')::text
                FROM app.compte WHERE actif AND profil IN ('AGENT', 'GESTIONNAIRE')""", String.class));
    }

    public boolean estAgentPossible(int idCompte) {
        return Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM app.compte WHERE id = ? AND actif AND profil IN ('AGENT', 'GESTIONNAIRE', 'DIRECTION', 'ADMIN'))""",
                Boolean.class, idCompte));
    }

    /**
     * Les bâtiments qui méritent une visite, avec leur motif et la raison, du plus pressant au moins
     * pressant : loyers en retard, état des lieux à faire, fiche à contrôler, travaux urgents.
     */
    public JsonNode suggestions(LocalDate jour, String perimetre, List<Object> params) {
        Object[] p = new Object[params.size() + 2];
        p[0] = jour;
        p[1] = jour;
        for (int i = 0; i < params.size(); i++) {
            p[i + 2] = params.get(i);
        }
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(x ORDER BY x.priorite, x.poids DESC, x.batiment), '[]')::text FROM (
                    SELECT v.id AS "idBatiment", v.nom AS batiment, v.quartier, v.commune, v.latitude, v.longitude, v.gestionnaire,
                           CASE WHEN edl.n > 0 THEN 'ETAT_DES_LIEUX' WHEN v.impaye > 0 THEN 'RECOUVREMENT'
                                WHEN v.validation = 'A_VALIDER' THEN 'CONTROLE' ELSE 'TRAVAUX' END AS motif,
                           CASE WHEN edl.n > 0 THEN 1 WHEN v.impaye > 0 THEN 2 WHEN v.validation = 'A_VALIDER' THEN 3 ELSE 4 END AS priorite,
                           coalesce(v.impaye, 0) AS poids,
                           concat_ws(' · ',
                               CASE WHEN edl.n > 0 THEN edl.n || ' état(s) des lieux à faire' END,
                               CASE WHEN v.impaye > 0 THEN v.mois_retard_max || ' mois de retard au plus, '
                                    || to_char(v.impaye, 'FM999G999G999') || ' FCFA impayés' END,
                               CASE WHEN v.validation = 'A_VALIDER' THEN 'fiche recensée à contrôler' END,
                               CASE WHEN v.interventions_urgentes > 0 THEN v.interventions_urgentes || ' intervention(s) urgente(s)' END
                           ) AS raison
                    FROM pat.v_batiment v
                    LEFT JOIN LATERAL (
                        SELECT count(*) AS n FROM loc.v_bail b
                        WHERE b.id_batiment = v.id AND (
                              (b.statut = 'PREAVIS' AND coalesce(b.date_sortie, b.date_preavis + 30) BETWEEN ?::date - 3 AND ?::date + 15
                               AND NOT EXISTS (SELECT 1 FROM loc.etat_des_lieux d WHERE d.id_bail = b.id AND d.type = 'SORTIE'))
                           OR (b.statut = 'ACTIF' AND b.date_effet >= current_date - 45
                               AND NOT EXISTS (SELECT 1 FROM loc.etat_des_lieux d WHERE d.id_bail = b.id AND d.type = 'ENTREE')))
                    ) edl ON true
                    WHERE v.archive_le IS NULL AND v.latitude IS NOT NULL AND (""" + perimetre + """
                    ) AND (edl.n > 0 OR v.impaye > 0 OR v.validation = 'A_VALIDER' OR v.interventions_urgentes > 0)
                    LIMIT 40) x""", String.class, p));
    }

    /** Un réglage numérique (point de départ, distance de pointage). */
    public double reglage(String cle, double defaut) {
        return jdbc.queryForList("SELECT valeur FROM ref.reglage WHERE cle = ?", String.class, cle).stream().findFirst()
                .map((v) -> {
                    try {
                        return Double.parseDouble(v.replace(',', '.'));
                    } catch (NumberFormatException e) {
                        return defaut;
                    }
                }).orElse(defaut);
    }

    /** Position des bâtiments, pour ordonner la tournée. */
    public List<double[]> positions(List<Integer> batiments) {
        return jdbc.query("SELECT id, ST_Y(geom), ST_X(geom) FROM pat.batiment WHERE id = ANY (?) AND geom IS NOT NULL",
                (rs, i) -> new double[]{rs.getInt(1), rs.getDouble(2), rs.getDouble(3)}, (Object) batiments.toArray(Integer[]::new));
    }
}
