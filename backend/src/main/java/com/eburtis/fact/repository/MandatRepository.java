package com.eburtis.fact.repository;

import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.MandatSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les mandats de gestion (pat.mandat et la vue pat.v_mandat qui calcule leur échéance). */
@Repository
public class MandatRepository {

    private static final String CHAMPS = """
            m.id, m.reference, m.id_proprietaire AS "idProprietaire", m.proprietaire, m.type,
            (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_MANDAT' AND code = m.type) AS "libelleType",
            m.date_debut AS "dateDebut", m.duree_mois AS "dureeMois", m.reconduction_tacite AS "reconductionTacite",
            m.taux_honoraires AS "tauxHonoraires", m.seuil_travaux AS "seuilTravaux", m.tous_les_biens AS "tousLesBiens",
            m.notes, m.resilie_le AS "resilieLe", m.motif_resiliation AS "motifResiliation",
            m.date_fin AS "dateFin", m.jours_restants AS "joursRestants", m.statut,
            coalesce((SELECT json_agg(json_build_object('id', b.id, 'nom', b.nom) ORDER BY b.nom)
                      FROM pat.mandat_batiment mb JOIN pat.batiment b ON b.id = mb.id_batiment
                      WHERE mb.id_mandat = m.id), '[]') AS batiments,
            m.cree_le AS "creeLe", m.cree_par AS "creePar", m.maj_le AS "majLe", m.maj_par AS "majPar",
            (SELECT x.document_nom FROM pat.mandat x WHERE x.id = m.id) AS "documentNom",
            (SELECT x.document_le FROM pat.mandat x WHERE x.id = m.id) AS "documentLe\"""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public MandatRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public JsonNode duProprietaire(int idProprietaire) {
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY t.\"dateDebut\" DESC), '[]')::text FROM (SELECT "
                + CHAMPS + " FROM pat.v_mandat m WHERE m.id_proprietaire = ?) t", String.class, idProprietaire));
    }

    public Optional<JsonNode> trouver(int id) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + CHAMPS + " FROM pat.v_mandat m WHERE m.id = ?) t",
                String.class, id).stream().findFirst().map(json::lire);
    }

    /** Le mandat en vigueur sur un bâtiment, s'il y en a un. */
    public Optional<JsonNode> actifSur(int idBatiment) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + CHAMPS
                + " FROM pat.v_mandat m WHERE m.id = pat.mandat_actif(?)) t", String.class, idBatiment).stream().findFirst().map(json::lire);
    }

    public int creer(MandatSaisieDto s, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO pat.mandat (id_proprietaire, type, date_debut, duree_mois, reconduction_tacite, taux_honoraires,
                    seuil_travaux, tous_les_biens, notes, cree_par)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.idProprietaire(), s.type(), s.dateDebut(), s.dureeMois(), s.reconductionTacite(), s.tauxHonoraires(),
                s.seuilTravaux(), s.tousLesBiens(), s.notes(), auteur);
        return id == null ? 0 : id;
    }

    public void modifier(MandatSaisieDto s, String auteur) {
        jdbc.update("""
                UPDATE pat.mandat SET type = ?, date_debut = ?, duree_mois = ?, reconduction_tacite = ?, taux_honoraires = ?,
                    seuil_travaux = ?, tous_les_biens = ?, notes = ?, maj_le = now(), maj_par = ?
                WHERE id = ?""", s.type(), s.dateDebut(), s.dureeMois(), s.reconductionTacite(), s.tauxHonoraires(),
                s.seuilTravaux(), s.tousLesBiens(), s.notes(), auteur, s.id());
    }

    public void poserBatiments(int idMandat, List<Integer> batiments) {
        jdbc.update("DELETE FROM pat.mandat_batiment WHERE id_mandat = ?", idMandat);
        for (Integer b : batiments) {
            jdbc.update("INSERT INTO pat.mandat_batiment (id_mandat, id_batiment) VALUES (?, ?)", idMandat, b);
        }
    }

    /** Combien de ces bâtiments appartiennent bien à ce propriétaire. */
    public int batimentsDuProprietaire(int idProprietaire, List<Integer> batiments) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM pat.batiment WHERE id_proprietaire = ? AND id = ANY (?::int[])",
                Integer.class, idProprietaire, "{" + String.join(",", batiments.stream().map(String::valueOf).toList()) + "}");
        return n == null ? 0 : n;
    }

    public void resilier(int id, java.time.LocalDate date, String motif, String auteur) {
        jdbc.update("UPDATE pat.mandat SET resilie_le = ?, motif_resiliation = ?, maj_le = now(), maj_par = ? WHERE id = ?",
                date, motif, auteur, id);
    }

    public void poserDocument(int id, String chemin, String nom, String auteur) {
        jdbc.update("UPDATE pat.mandat SET document_chemin = ?, document_nom = ?, document_le = now(), maj_le = now(), maj_par = ? WHERE id = ?",
                chemin, nom, auteur, id);
    }

    /** Le chemin et le nom du document d'un mandat, et son propriétaire. */
    public Optional<Object[]> document(int id) {
        return jdbc.query("SELECT document_chemin, document_nom, id_proprietaire FROM pat.mandat WHERE id = ? AND document_chemin IS NOT NULL",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getInt(3)}, id).stream().findFirst();
    }
}
