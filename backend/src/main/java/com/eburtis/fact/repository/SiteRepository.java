package com.eburtis.fact.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Ce que le site public lit : les annonces en ligne seulement, sans rien de privé (ni gestionnaire,
 * ni propriétaire, ni locataire) et avec la position choisie par l'agence (exacte ou approchée).
 */
@Repository
public class SiteRepository {

    /** Une vignette du catalogue (§ 3.2). */
    private static final String VIGNETTE = """
            a.id, a.slug, a.reference, a.titre, a.operation, a.prix, a.charges, a.categorie, a.libelle_type AS "libelleType",
            a.commune, a.id_commune AS "idCommune", a.quartier, a.id_quartier AS "idQuartier", a.surface, a.pieces,
            a.chambres, a.salles_eau AS "sallesEau", a.equipements, a.latitude, a.longitude, a.position_exacte AS "positionExacte",
            a.a_la_une AS "aLaUne", a.publiee_le AS "publieeLe", a.photos,
            (SELECT p.id FROM web.annonce_photo p WHERE p.id_annonce = a.id ORDER BY p.ordre, p.id LIMIT 1) AS "idPhoto\"""";

    private static final String EN_LIGNE = "a.statut = 'PUBLIEE' AND a.statut_lot NOT IN ('OCCUPE', 'EN_PREAVIS', 'HORS_GESTION')";

    /** Les critères de recherche du § 3.2 ; tout est facultatif. */
    public record Criteres(String operation, List<String> types, Integer idCommune, Integer idQuartier, Long prixMin,
                           Long prixMax, Integer pieces, Integer surface, List<String> options, String tri,
                           Double ouest, Double sud, Double est, Double nord) {
    }

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public SiteRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JsonNode rechercher(Criteres c) {
        StringBuilder w = new StringBuilder(EN_LIGNE);
        List<Object> p = new ArrayList<>();
        if (c.operation() != null) {
            w.append(" AND a.operation = ?");
            p.add(c.operation());
        }
        if (c.types() != null && !c.types().isEmpty()) {
            w.append(" AND a.categorie = ANY (?::text[])");
            p.add("{" + String.join(",", c.types().stream().map(t -> "\"" + t.replace("\"", "") + "\"").toList()) + "}");
        }
        ajouter(w, p, " AND a.id_commune = ?", c.idCommune());
        ajouter(w, p, " AND a.id_quartier = ?", c.idQuartier());
        ajouter(w, p, " AND a.prix >= ?", c.prixMin());
        ajouter(w, p, " AND a.prix <= ?", c.prixMax());
        ajouter(w, p, " AND coalesce(a.pieces, 0) >= ?", c.pieces());
        ajouter(w, p, " AND coalesce(a.surface, 0) >= ?", c.surface());
        if (c.options() != null && !c.options().isEmpty()) {
            w.append(" AND a.equipements @> ?::text[]");
            p.add("{" + String.join(",", c.options().stream().map(o -> o.replaceAll("[^A-Z_]", "")).toList()) + "}");
        }
        // La carte : seuls les biens placés dans le cadre visible
        if (c.ouest() != null && c.sud() != null && c.est() != null && c.nord() != null) {
            w.append(" AND a.longitude BETWEEN ? AND ? AND a.latitude BETWEEN ? AND ?");
            p.add(c.ouest());
            p.add(c.est());
            p.add(c.sud());
            p.add(c.nord());
        }
        String ordre = switch (c.tri() == null ? "" : c.tri()) {
            case "prix-croissant" -> "t.prix, t.\"publieeLe\" DESC";
            case "prix-decroissant" -> "t.prix DESC, t.\"publieeLe\" DESC";
            case "surface" -> "t.surface DESC NULLS LAST, t.\"publieeLe\" DESC";
            default -> "t.\"publieeLe\" DESC NULLS LAST, t.id DESC";
        };
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY " + ordre + "), '[]')::text FROM (SELECT " + VIGNETTE
                + " FROM web.v_annonce a WHERE " + w + " LIMIT 300) t", String.class, p.toArray()));
    }

    private static void ajouter(StringBuilder w, List<Object> p, String condition, Object valeur) {
        if (valeur != null) {
            w.append(condition);
            p.add(valeur);
        }
    }

    /** Les biens à la une ; à défaut, les dernières publications (§ 3.1). */
    public JsonNode aLaUne(int nombre) {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t), '[]')::text FROM (SELECT " + VIGNETTE
                + " FROM web.v_annonce a WHERE " + EN_LIGNE + " ORDER BY a.a_la_une DESC, a.publiee_le DESC NULLS LAST LIMIT ?) t",
                String.class, nombre));
    }

    /** La fiche d'un bien en ligne (§ 3.3), ses photos et ses équipements en clair. */
    public Optional<JsonNode> fiche(String slug) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + VIGNETTE + """
                , a.description, a.caution_mois AS "cautionMois", a.avance_mois AS "avanceMois", a.etage,
                  (SELECT coalesce(json_agg(json_build_object('id', p.id, 'legende', p.legende, 'largeur', p.largeur,
                          'hauteur', p.hauteur) ORDER BY p.ordre, p.id), '[]') FROM web.annonce_photo p WHERE p.id_annonce = a.id) AS galerie,
                  (SELECT coalesce(json_agg(v.libelle ORDER BY v.ordre), '[]') FROM ref.valeur v
                    WHERE v.domaine = 'EQUIPEMENT' AND v.code = ANY (a.equipements)) AS "libellesEquipements"
                FROM web.v_annonce a WHERE a.slug = ? AND\s""" + EN_LIGNE + ") t", String.class, slug).stream().findFirst().map(json::lire);
    }

    /** Biens similaires : même opération, même commune, prix à 35 % près. */
    public JsonNode similaires(int id, String operation, int idCommune, long prix) {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t), '[]')::text FROM (SELECT " + VIGNETTE
                + " FROM web.v_annonce a WHERE " + EN_LIGNE + " AND a.id <> ? AND a.operation = ?"
                + " AND (a.id_commune = ? OR a.prix BETWEEN ? AND ?) ORDER BY (a.id_commune = ?) DESC, abs(a.prix - ?) LIMIT 4) t",
                String.class, id, operation, idCommune, (long) (prix * 0.65), (long) (prix * 1.35), idCommune, prix));
    }

    /** Le chemin d'une photo, seulement si son annonce est en ligne. */
    public Optional<String> photoPublique(int idPhoto) {
        return jdbc.queryForList("SELECT p.chemin FROM web.annonce_photo p JOIN web.v_annonce a ON a.id = p.id_annonce WHERE p.id = ? AND "
                + EN_LIGNE, String.class, idPhoto).stream().findFirst();
    }

    /** L'annonce en ligne d'une adresse de fiche : son identifiant, son bâtiment et le gestionnaire de celui-ci. */
    public Optional<Object[]> annonceEnLigne(String slug) {
        return jdbc.query("""
                SELECT a.id, a.id_batiment, (SELECT c.id FROM app.compte c WHERE c.id = a.id_gestionnaire AND c.actif), a.titre
                FROM web.v_annonce a WHERE a.slug = ? AND\s""" + EN_LIGNE,
                (rs, i) -> new Object[]{rs.getInt(1), rs.getInt(2), rs.getObject(3), rs.getString(4)}, slug).stream().findFirst();
    }

    /** Les chiffres clés de l'accueil (§ 3.1), calculés sur le patrimoine. */
    public JsonNode chiffres() {
        return json.lire(jdbc.queryForObject("""
                SELECT json_build_object(
                    'batiments', (SELECT count(*) FROM pat.batiment WHERE archive_le IS NULL),
                    'lots', (SELECT count(*) FROM pat.lot l JOIN pat.batiment b ON b.id = l.id_batiment
                             WHERE l.archive_le IS NULL AND b.archive_le IS NULL AND l.statut <> 'HORS_GESTION'),
                    'communes', (SELECT count(DISTINCT id_commune) FROM pat.batiment WHERE archive_le IS NULL),
                    'enLigne', (SELECT count(*) FROM web.v_annonce a WHERE\s""" + EN_LIGNE + "))::text", String.class));
    }
}
