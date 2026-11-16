package com.eburtis.fact.repository;

import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les lots d'un bâtiment (table pat.lot). L'accès au bâtiment est contrôlé avant d'arriver ici. */
@Repository
public class LotRepository {

    private static final String COLONNES = """
            l.id, l.id_batiment AS "idBatiment", l.numero, l.etage, l.type,
            (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_LOT' AND code = l.type) AS "libelleType",
            l.surface, l.pieces, l.chambres, l.salles_eau AS "sallesEau", l.equipements,
            l.loyer_reference AS "loyerReference", l.charges, l.statut,
            (SELECT libelle FROM ref.valeur WHERE domaine = 'STATUT_LOT' AND code = l.statut) AS "libelleStatut",
            l.statut_depuis AS "statutDepuis", l.description, l.archive_le AS "archiveLe",
            l.cree_le AS "creeLe", l.cree_par AS "creePar", l.maj_le AS "majLe", l.maj_par AS "majPar\"""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public LotRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JsonNode duBatiment(int idBatiment, boolean archives) {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY t.etage, t.numero), '[]')::text FROM (SELECT "
                + COLONNES + " FROM pat.lot l WHERE l.id_batiment = ? AND (? OR l.archive_le IS NULL)) t",
                String.class, idBatiment, archives));
    }

    public Optional<JsonNode> trouver(int id) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + COLONNES + " FROM pat.lot l WHERE l.id = ?) t",
                String.class, id).stream().findFirst().map(json::lire);
    }

    public Optional<Integer> batimentDe(int id) {
        return jdbc.queryForList("SELECT id_batiment FROM pat.lot WHERE id = ?", Integer.class, id).stream().findFirst();
    }

    public boolean numeroPris(int idBatiment, String numero) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pat.lot WHERE id_batiment = ? AND lower(numero) = lower(?) AND archive_le IS NULL",
                Integer.class, idBatiment, numero);
        return n != null && n > 0;
    }

    public int creer(LotSaisieDto s, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO pat.lot (id_batiment, numero, etage, type, surface, pieces, chambres, salles_eau, equipements,
                    loyer_reference, charges, statut, description, cree_par)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.idBatiment(), s.numero(), s.etage(), s.type(), s.surface(), s.pieces(), s.chambres(), s.sallesEau(),
                litteral(s.equipements()), s.loyerReference(), s.charges(), s.statut(), s.description(), auteur);
        return id == null ? 0 : id;
    }

    public int modifier(LotSaisieDto s, String auteur) {
        return jdbc.update("""
                UPDATE pat.lot SET numero = ?, etage = ?, type = ?, surface = ?, pieces = ?, chambres = ?, salles_eau = ?,
                    equipements = ?::text[], loyer_reference = ?, charges = ?, statut = ?, description = ?,
                    maj_le = now(), maj_par = ?
                WHERE id = ?""",
                s.numero(), s.etage(), s.type(), s.surface(), s.pieces(), s.chambres(), s.sallesEau(),
                litteral(s.equipements()), s.loyerReference(), s.charges(), s.statut(), s.description(), auteur, s.id());
    }

    public int archiver(int id, boolean archive, String auteur) {
        return jdbc.update("UPDATE pat.lot SET archive_le = CASE WHEN ? THEN now() END, maj_le = now(), maj_par = ? WHERE id = ?",
                archive, auteur, id);
    }

    private static String litteral(List<String> valeurs) {
        return "{" + String.join(",", valeurs == null ? List.<String>of() : valeurs) + "}";
    }
}
