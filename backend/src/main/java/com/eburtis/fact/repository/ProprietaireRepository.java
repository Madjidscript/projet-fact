package com.eburtis.fact.repository;

import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les propriétaires mandants (table pat.proprietaire). La base compose elle-même le JSON rendu. */
@Repository
public class ProprietaireRepository {

    /** Ce que tout lecteur du patrimoine voit d'un propriétaire. */
    private static final String IDENTITE = """
            p.id, p.reference, p.type_personne AS "typePersonne", p.nom, p.prenoms,
            btrim(p.nom || ' ' || coalesce(p.prenoms, '')) AS "nomComplet",
            p.origine, p.archive_le AS "archiveLe", p.id_compte AS "idCompte",
            (SELECT count(*) FROM pat.batiment b WHERE b.id_proprietaire = p.id AND b.archive_le IS NULL) AS batiments""";

    /** Ses coordonnées : réservées aux profils qui gèrent le patrimoine (§ 8). */
    private static final String CONTACTS = """
            , p.telephone, p.telephone_2 AS "telephone2", p.email, p.adresse,
            p.piece_type AS "pieceType", p.piece_numero AS "pieceNumero",
            p.reversement_mode AS "reversementMode", p.reversement_coordonnees AS "reversementCoordonnees",
            p.notes, p.cree_le AS "creeLe", p.cree_par AS "creePar", p.maj_le AS "majLe", p.maj_par AS "majPar\"""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public ProprietaireRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Tous les propriétaires, ou le seul rattaché au compte {@code idCompte} (espace propriétaire). */
    public JsonNode lister(boolean contacts, boolean archives, Integer idCompte) {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY t.nom, t.prenoms), '[]')::text FROM (SELECT "
                + IDENTITE + (contacts ? CONTACTS : "") + """
                 FROM pat.proprietaire p
                WHERE (? OR p.archive_le IS NULL) AND (?::integer IS NULL OR p.id_compte = ?::integer)) t""",
                String.class, archives, idCompte, idCompte));
    }

    public Optional<JsonNode> trouver(int id, boolean contacts) {
        List<String> l = jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + IDENTITE + (contacts ? CONTACTS : "")
                + " FROM pat.proprietaire p WHERE p.id = ?) t", String.class, id);
        return l.stream().findFirst().map(json::lire);
    }

    /** Le compte de l'espace propriétaire rattaché à cette fiche, s'il y en a un. */
    public Optional<Integer> compteDe(int id) {
        return jdbc.queryForList("SELECT id_compte FROM pat.proprietaire WHERE id = ?", Integer.class, id).stream()
                .filter(java.util.Objects::nonNull).findFirst();
    }

    /** Un propriétaire non archivé de même nom complet, pour le contrôle des doublons à l'import. */
    public Optional<Integer> parNom(String cle) {
        return jdbc.queryForList("""
                SELECT id FROM pat.proprietaire
                WHERE archive_le IS NULL
                  AND btrim(regexp_replace(lower(unaccent(nom || ' ' || coalesce(prenoms, ''))), '[^a-z0-9]+', ' ', 'g')) = ?
                ORDER BY id LIMIT 1""", Integer.class, cle).stream().findFirst();
    }

    public int creer(ProprietaireSaisieDto s, String origine, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO pat.proprietaire (type_personne, nom, prenoms, telephone, telephone_2, email, adresse, piece_type,
                    piece_numero, reversement_mode, reversement_coordonnees, id_compte, notes, origine, cree_par)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.typePersonne(), s.nom(), s.prenoms(), s.telephone(), s.telephone2(), s.email(), s.adresse(), s.pieceType(),
                s.pieceNumero(), s.reversementMode(), s.reversementCoordonnees(), s.idCompte(), s.notes(), origine, auteur);
        return id == null ? 0 : id;
    }

    public int modifier(ProprietaireSaisieDto s, String auteur) {
        return jdbc.update("""
                UPDATE pat.proprietaire SET type_personne = ?, nom = ?, prenoms = ?, telephone = ?, telephone_2 = ?, email = ?,
                    adresse = ?, piece_type = ?, piece_numero = ?, reversement_mode = ?, reversement_coordonnees = ?,
                    id_compte = ?, notes = ?, maj_le = now(), maj_par = ?
                WHERE id = ?""",
                s.typePersonne(), s.nom(), s.prenoms(), s.telephone(), s.telephone2(), s.email(), s.adresse(), s.pieceType(),
                s.pieceNumero(), s.reversementMode(), s.reversementCoordonnees(), s.idCompte(), s.notes(), auteur, s.id());
    }

    /** Archive ou restaure ; rend le nombre de fiches touchées. */
    public int archiver(int id, boolean archive, String auteur) {
        return jdbc.update("UPDATE pat.proprietaire SET archive_le = CASE WHEN ? THEN now() END, maj_le = now(), maj_par = ? WHERE id = ?",
                archive, auteur, id);
    }

    public int batimentsActifs(int id) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pat.batiment WHERE id_proprietaire = ? AND archive_le IS NULL", Integer.class, id);
        return n == null ? 0 : n;
    }

    /** Les comptes de profil PROPRIETAIRE encore libres, ou déjà rattachés à cette fiche. */
    public JsonNode comptesProprietaires() {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.nom), '[]')::text FROM (
                    SELECT c.id, c.nom, c.identifiant, p.id AS "idProprietaire"
                    FROM app.compte c LEFT JOIN pat.proprietaire p ON p.id_compte = c.id
                    WHERE c.profil = 'PROPRIETAIRE' AND c.actif) t""", String.class));
    }

    public void rattacherCompte(int id, int idCompte, String auteur) {
        jdbc.update("UPDATE pat.proprietaire SET id_compte = ?, maj_le = now(), maj_par = ? WHERE id = ?", idCompte, auteur, id);
    }
}
