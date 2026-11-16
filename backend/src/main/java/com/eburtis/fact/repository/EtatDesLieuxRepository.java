package com.eburtis.fact.repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les états des lieux d'entrée et de sortie, leurs photos et leurs signatures (§ 4.6). */
@Repository
public class EtatDesLieuxRepository {

    private static final String EDL = """
            SELECT d.id, d.reference, d.cle, d.id_bail AS "idBail", d.type, d.fait_le AS "faitLe", d.pieces, d.compteurs,
                   d.cles_remises AS "clesRemises", d.observations, d.signataire, d.signature_occupant AS "signatureOccupant",
                   d.signature_agent AS "signatureAgent", d.latitude, d.longitude, d.cree_le AS "creeLe", d.cree_par AS "creePar",
                   vb.reference AS "referenceBail", vb.occupant, vb.reference_occupant AS "referenceOccupant", vb.numero_lot AS "numeroLot",
                   vb.etage, (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = vb.type_lot) AS "libelleTypeLot",
                   vb.date_effet AS "dateEffet", vb.id_batiment AS "idBatiment", v.nom AS batiment, v.adresse, v.quartier, v.commune,
                   v.proprietaire,
                   (SELECT coalesce(json_agg(json_build_object('id', p.id, 'piece', p.piece) ORDER BY p.ordre, p.id), '[]')
                    FROM loc.etat_des_lieux_photo p WHERE p.id_edl = d.id) AS photos
            FROM loc.etat_des_lieux d
            JOIN loc.v_bail vb ON vb.id = d.id_bail
            JOIN pat.v_batiment v ON v.id = vb.id_batiment""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public EtatDesLieuxRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<JsonNode> trouver(int id) {
        return jdbc.queryForList("SELECT row_to_json(x)::text FROM (" + EDL + " WHERE d.id = ?) x", String.class, id)
                .stream().findFirst().map(json::lire);
    }

    public Optional<Integer> parCle(UUID cle) {
        return jdbc.queryForList("SELECT id FROM loc.etat_des_lieux WHERE cle = ?", Integer.class, cle).stream().findFirst();
    }

    public Optional<JsonNode> duBail(int idBail, String type) {
        return jdbc.queryForList("SELECT row_to_json(x)::text FROM (" + EDL + " WHERE d.id_bail = ? AND d.type = ?) x",
                String.class, idBail, type).stream().findFirst().map(json::lire);
    }

    /** Les états des lieux d'un bail, sans le détail des pièces. */
    public JsonNode listeDuBail(int idBail) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', id, 'reference', reference, 'type', type, 'faitLe', fait_le,
                       'signataire', signataire, 'creePar', cree_par) ORDER BY fait_le), '[]')::text
                FROM loc.etat_des_lieux WHERE id_bail = ?""", String.class, idBail));
    }

    /** Le bail, s'il est dans le périmètre du compte (condition posée sur la vue pat.v_batiment, alias v). */
    public Optional<JsonNode> bail(int idBail, String perimetre, List<Object> params) {
        Object[] p = new Object[params.size() + 1];
        p[0] = idBail;
        for (int i = 0; i < params.size(); i++) {
            p[i + 1] = params.get(i);
        }
        return jdbc.queryForList("""
                SELECT row_to_json(x)::text FROM (
                    SELECT vb.id, vb.reference, vb.statut, vb.occupant, vb.numero_lot AS "numeroLot", vb.date_effet AS "dateEffet",
                           vb.date_sortie AS "dateSortie", vb.id_batiment AS "idBatiment", v.nom AS batiment
                    FROM loc.v_bail vb JOIN pat.v_batiment v ON v.id = vb.id_batiment
                    WHERE vb.id = ? AND (""" + perimetre + ")) x", String.class, p).stream().findFirst().map(json::lire);
    }

    public int creer(UUID cle, int idBail, String type, OffsetDateTime faitLe, String pieces, String compteurs, Integer clesRemises,
                     String observations, String signataire, String signatureOccupant, String signatureAgent, Double latitude,
                     Double longitude, String auteur) {
        Integer id = jdbc.queryForList("""
                INSERT INTO loc.etat_des_lieux (cle, id_bail, type, fait_le, pieces, compteurs, cles_remises, observations, signataire,
                                                signature_occupant, signature_agent, latitude, longitude, cree_par)
                VALUES (?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (cle) DO NOTHING RETURNING id""", Integer.class,
                cle, idBail, type, faitLe, pieces, compteurs, clesRemises, observations, signataire, signatureOccupant, signatureAgent,
                latitude, longitude, auteur).stream().findFirst().orElse(null);
        return id == null ? 0 : id;
    }

    public void ajouterPhoto(int idEdl, String piece, String chemin, int ordre) {
        jdbc.update("INSERT INTO loc.etat_des_lieux_photo (id_edl, piece, chemin, ordre) VALUES (?, ?, ?, ?)", idEdl, piece, chemin, ordre);
    }

    /** Le chemin d'une photo et l'état des lieux qui la porte. */
    public Optional<Object[]> photo(int idPhoto) {
        return jdbc.query("SELECT chemin, id_edl FROM loc.etat_des_lieux_photo WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, idPhoto).stream().findFirst();
    }

    /** Les chemins des photos d'un état des lieux, pièce par pièce, pour le PDF. */
    public List<String[]> cheminsPhotos(int idEdl) {
        return jdbc.query("SELECT piece, chemin FROM loc.etat_des_lieux_photo WHERE id_edl = ? ORDER BY ordre, id",
                (rs, i) -> new String[]{rs.getString(1), rs.getString(2)}, idEdl);
    }
}
