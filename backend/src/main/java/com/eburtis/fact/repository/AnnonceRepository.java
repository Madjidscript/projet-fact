package com.eburtis.fact.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.AnnonceSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les annonces du site (web.annonce, vue web.v_annonce) et leurs photos, côté application. */
@Repository
public class AnnonceRepository {

    static final String CHAMPS = """
            a.id, a.reference, a.slug, a.id_lot AS "idLot", a.operation, a.titre, a.description, a.prix, a.charges,
            a.caution_mois AS "cautionMois", a.avance_mois AS "avanceMois", a.position_exacte AS "positionExacte",
            a.a_la_une AS "aLaUne", a.statut,
            (SELECT libelle FROM ref.valeur WHERE domaine = 'STATUT_ANNONCE' AND code = a.statut) AS "libelleStatut",
            a.motif, a.soumise_le AS "soumiseLe", a.soumise_par AS "soumisePar", a.publiee_le AS "publieeLe",
            a.publiee_par AS "publieePar", a.retiree_le AS "retireeLe", a.retiree_par AS "retireePar",
            a.cree_le AS "creeLe", a.cree_par AS "creePar", a.maj_le AS "majLe", a.maj_par AS "majPar",
            a.numero_lot AS "numeroLot", a.type_lot AS "typeLot", a.libelle_type AS "libelleType", a.surface, a.pieces,
            a.chambres, a.salles_eau AS "sallesEau", a.etage, a.equipements, a.statut_lot AS "statutLot",
            a.id_batiment AS "idBatiment", a.batiment, a.commune, a.quartier, a.latitude_exacte AS "latitude",
            a.longitude_exacte AS "longitude", a.photos,
            (SELECT p.id FROM web.annonce_photo p WHERE p.id_annonce = a.id ORDER BY p.ordre, p.id LIMIT 1) AS "idPhoto\"""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public AnnonceRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Les annonces des bâtiments du périmètre ; {@code statut} nul : toutes. */
    public JsonNode lister(String perimetre, List<Object> paramsPerimetre, String statut) {
        List<Object> p = new ArrayList<>(paramsPerimetre);
        p.add(statut);
        p.add(statut);
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY t.\"majLe\" DESC NULLS LAST, t.\"creeLe\" DESC), '[]')::text FROM (SELECT "
                + CHAMPS + " FROM web.v_annonce a WHERE EXISTS (SELECT 1 FROM pat.v_batiment v WHERE v.id = a.id_batiment AND "
                + perimetre + ") AND (?::text IS NULL OR a.statut = ?::text)) t", String.class, p.toArray()));
    }

    public Optional<JsonNode> trouver(int id) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + CHAMPS + " FROM web.v_annonce a WHERE a.id = ?) t",
                String.class, id).stream().findFirst().map(json::lire);
    }

    /** L'annonce vivante d'un lot (brouillon, à valider, en ligne ou à revoir), s'il en a une. */
    public Optional<JsonNode> vivanteDuLot(int idLot) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + CHAMPS
                + " FROM web.v_annonce a WHERE a.id_lot = ? AND a.statut IN ('BROUILLON', 'A_VALIDER', 'PUBLIEE', 'REFUSEE')) t",
                String.class, idLot).stream().findFirst().map(json::lire);
    }

    public JsonNode photos(int idAnnonce) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', p.id, 'ordre', p.ordre, 'legende', p.legende,
                       'largeur', p.largeur, 'hauteur', p.hauteur) ORDER BY p.ordre, p.id), '[]')::text
                FROM web.annonce_photo p WHERE p.id_annonce = ?""", String.class, idAnnonce));
    }

    public int creer(AnnonceSaisieDto s, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO web.annonce (id_lot, operation, titre, slug, description, prix, charges, caution_mois, avance_mois,
                    position_exacte, cree_par)
                VALUES (?, ?, ?, 'a-' || gen_random_uuid(), ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.idLot(), s.operation(), s.titre(), s.description(), s.prix(), s.charges(), s.cautionMois(), s.avanceMois(),
                s.positionExacte(), auteur);
        poserSlug(id);
        return id == null ? 0 : id;
    }

    public void modifier(AnnonceSaisieDto s, String auteur) {
        jdbc.update("""
                UPDATE web.annonce SET operation = ?, titre = ?, description = ?, prix = ?, charges = ?, caution_mois = ?,
                    avance_mois = ?, position_exacte = ?, maj_le = now(), maj_par = ?
                WHERE id = ?""", s.operation(), s.titre(), s.description(), s.prix(), s.charges(), s.cautionMois(),
                s.avanceMois(), s.positionExacte(), auteur, s.id());
        poserSlug(s.id());
    }

    /** « appartement-f3-cite-sir-12 » : lisible, stable, unique grâce à l'identifiant. */
    private void poserSlug(int id) {
        jdbc.update("""
                UPDATE web.annonce SET slug = trim(BOTH '-' FROM left(regexp_replace(lower(unaccent(titre)), '[^a-z0-9]+', '-', 'g'), 80))
                    || '-' || id WHERE id = ?""", id);
    }

    public void statut(int id, String statut, String motif, String auteur) {
        String quand = switch (statut) {
            case "A_VALIDER" -> ", soumise_le = now(), soumise_par = ?";
            case "PUBLIEE" -> ", publiee_le = now(), publiee_par = ?";
            case "RETIREE" -> ", retiree_le = now(), retiree_par = ?";
            default -> ", maj_par = ?";
        };
        jdbc.update("UPDATE web.annonce SET statut = ?, motif = ?, maj_le = now()" + quand + " WHERE id = ?", statut, motif, auteur, id);
    }

    public void aLaUne(int id, boolean une, String auteur) {
        jdbc.update("UPDATE web.annonce SET a_la_une = ?, maj_le = now(), maj_par = ? WHERE id = ?", une, auteur, id);
    }

    public int ajouterPhoto(int idAnnonce, String chemin, int largeur, int hauteur, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO web.annonce_photo (id_annonce, chemin, largeur, hauteur, ordre, cree_par)
                VALUES (?, ?, ?, ?, (SELECT coalesce(max(ordre), -1) + 1 FROM web.annonce_photo WHERE id_annonce = ?), ?)
                RETURNING id""", Integer.class, idAnnonce, chemin, largeur, hauteur, idAnnonce, auteur);
        return id == null ? 0 : id;
    }

    /** Le chemin d'une photo et son annonce. */
    public Optional<Object[]> photo(int idPhoto) {
        return jdbc.query("SELECT chemin, id_annonce FROM web.annonce_photo WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, idPhoto).stream().findFirst();
    }

    public void supprimerPhoto(int idPhoto) {
        jdbc.update("DELETE FROM web.annonce_photo WHERE id = ?", idPhoto);
    }

    /** Place les photos dans l'ordre donné ; la première devient la photo principale. */
    public void ordonner(int idAnnonce, List<Integer> ids) {
        for (int i = 0; i < ids.size(); i++) {
            jdbc.update("UPDATE web.annonce_photo SET ordre = ? WHERE id = ? AND id_annonce = ?", i, ids.get(i), idAnnonce);
        }
    }

    /** Le lot lui-même passe « À louer » ou « À vendre » quand son annonce est en ligne. */
    public void proposerLot(int idLot, String operation, String auteur) {
        jdbc.update("""
                UPDATE pat.lot SET statut = ?, maj_le = now(), maj_par = ?
                WHERE id = ? AND statut IN ('VACANT', 'A_LOUER', 'A_VENDRE', 'RESERVE')""",
                "VENTE".equals(operation) ? "A_VENDRE" : "A_LOUER", auteur, idLot);
    }
}
