package com.eburtis.fact.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Recensements reçus du terrain et photos des bâtiments. */
@Repository
public class TerrainRepository {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public TerrainRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Le bâtiment déjà créé par ce recensement, si l'envoi a déjà abouti. */
    public Optional<Integer> dejaRecu(UUID cle) {
        return jdbc.queryForList("SELECT id_batiment FROM pat.recensement WHERE cle = ?", Integer.class, cle).stream().findFirst();
    }

    public void consigner(UUID cle, int idBatiment, OffsetDateTime saisiLe, String auteur) {
        jdbc.update("INSERT INTO pat.recensement (cle, id_batiment, saisi_le, par) VALUES (?, ?, ?, ?)", cle, idBatiment, saisiLe, auteur);
    }

    public void ajouterPhoto(int idBatiment, String chemin, int largeur, int hauteur, OffsetDateTime priseLe, String auteur) {
        jdbc.update("""
                INSERT INTO pat.batiment_photo (id_batiment, chemin, largeur, hauteur, ordre, prise_le, cree_par)
                VALUES (?, ?, ?, ?, (SELECT coalesce(max(ordre), -1) + 1 FROM pat.batiment_photo WHERE id_batiment = ?), ?, ?)""",
                idBatiment, chemin, largeur, hauteur, idBatiment, priseLe, auteur);
    }

    public JsonNode photos(int idBatiment) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', id, 'largeur', largeur, 'hauteur', hauteur, 'priseLe', prise_le,
                       'creePar', cree_par) ORDER BY ordre, id), '[]')::text
                FROM pat.batiment_photo WHERE id_batiment = ?""", String.class, idBatiment));
    }

    public Optional<Object[]> photo(int idPhoto) {
        return jdbc.query("SELECT chemin, id_batiment FROM pat.batiment_photo WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, idPhoto).stream().findFirst();
    }

    /** Ce que le téléphone garde pour travailler sans réseau : les propriétaires à choisir. */
    public JsonNode proprietairesPourTerrain() {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', id, 'nom', btrim(nom || ' ' || coalesce(prenoms, '')), 'reference', reference)
                       ORDER BY (reference = 'PRO-SITE') DESC, nom), '[]')::text
                FROM pat.proprietaire WHERE archive_le IS NULL""", String.class));
    }

    /** Le propriétaire « à préciser » : celui d'un bâtiment recensé dont l'agent ignore le propriétaire. */
    public Optional<Integer> proprietaireAPreciser() {
        return jdbc.queryForList("SELECT id FROM pat.proprietaire WHERE reference = 'PRO-SITE' AND archive_le IS NULL", Integer.class)
                .stream().findFirst();
    }

    private static Object[] avec(List<Object> params) {
        return params.toArray();
    }

    /**
     * Les baux en cours du périmètre, de quoi encaisser ou faire un état des lieux sans réseau :
     * lot, occupant et son téléphone, loyer du mois, impayés, états des lieux déjà faits.
     */
    public JsonNode bauxPourTerrain(String perimetre, List<Object> params) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(x ORDER BY x.batiment, x."numeroLot"), '[]')::text FROM (
                    SELECT vb.id AS "idBail", vb.reference, vb.statut, vb.id_lot AS "idLot", vb.numero_lot AS "numeroLot", vb.etage,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = vb.type_lot) AS "typeLot",
                           vb.id_batiment AS "idBatiment", v.nom AS batiment, vb.occupant, vb.telephone_occupant AS telephone,
                           (vb.loyer + vb.charges) AS "loyerMensuel", vb.impaye, vb.mois_retard AS "moisRetard", vb.solde,
                           vb.date_effet AS "dateEffet", vb.date_sortie AS "dateSortie",
                           EXISTS (SELECT 1 FROM loc.etat_des_lieux d WHERE d.id_bail = vb.id AND d.type = 'ENTREE') AS "edlEntree",
                           EXISTS (SELECT 1 FROM loc.etat_des_lieux d WHERE d.id_bail = vb.id AND d.type = 'SORTIE') AS "edlSortie"
                    FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment
                    WHERE vb.statut IN ('ACTIF', 'PREAVIS') AND v.archive_le IS NULL AND (""" + perimetre + ")) x",
                String.class, avec(params)));
    }

    /** Les bâtiments du périmètre placés sur la carte, pour la tournée. */
    public JsonNode batimentsPourTerrain(String perimetre, List<Object> params) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', v.id, 'nom', v.nom, 'quartier', v.quartier, 'commune', v.commune,
                       'adresse', v.adresse, 'latitude', v.latitude, 'longitude', v.longitude) ORDER BY v.nom), '[]')::text
                FROM pat.v_batiment v WHERE v.archive_le IS NULL AND v.latitude IS NOT NULL AND (""" + perimetre + ")",
                String.class, avec(params)));
    }
}
