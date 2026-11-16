package com.eburtis.fact.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Les bâtiments (table pat.batiment, vue pat.v_batiment). Chaque lecture reçoit la condition
 * de périmètre du compte connecté (PerimetreService) : on ne lit jamais « tout » par défaut.
 */
@Repository
public class BatimentRepository {

    private static final String COLONNES = """
            v.id, v.reference, v.nom, v.type, v.adresse, v.id_commune AS "idCommune", v.commune,
            v.id_quartier AS "idQuartier", v.quartier, v.latitude, v.longitude, v.precision_gps AS "precisionGps",
            v.niveaux, v.annee_construction AS "anneeConstruction", v.description,
            v.id_proprietaire AS "idProprietaire", v.reference_proprietaire AS "referenceProprietaire", v.proprietaire,
            v.id_gestionnaire AS "idGestionnaire", v.gestionnaire, v.id_suppleant AS "idSuppleant", v.suppleant,
            v.validation, v.origine, v.archive_le AS "archiveLe", v.cree_le AS "creeLe", v.cree_par AS "creePar",
            v.maj_le AS "majLe", v.maj_par AS "majPar",
            v.lots, v.lots_occupes AS "lotsOccupes", v.lots_vacants AS "lotsVacants", v.loyers_reference AS "loyersReference",
            v.baux_actifs AS "bauxActifs", v.impaye, v.mois_retard_max AS "moisRetardMax", v.loyers_baux AS "loyersBaux",
            v.interventions_ouvertes AS "interventionsOuvertes", v.interventions_urgentes AS "interventionsUrgentes\"""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public BatimentRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** Les bâtiments du périmètre ; {@code idProprietaire} non nul restreint à ceux d'un propriétaire. */
    public JsonNode lister(String perimetre, List<Object> paramsPerimetre, boolean archives, Integer idProprietaire) {
        List<Object> params = new ArrayList<>(paramsPerimetre);
        params.add(archives);
        params.add(idProprietaire);
        params.add(idProprietaire);
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY t.nom), '[]')::text FROM (SELECT " + COLONNES
                + " FROM pat.v_batiment v WHERE " + perimetre
                + " AND (? OR v.archive_le IS NULL) AND (?::integer IS NULL OR v.id_proprietaire = ?::integer)) t",
                String.class, params.toArray()));
    }

    public Optional<JsonNode> trouver(int id, String perimetre, List<Object> paramsPerimetre) {
        List<Object> params = new ArrayList<>(paramsPerimetre);
        params.add(id);
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + COLONNES
                + " FROM pat.v_batiment v WHERE " + perimetre + " AND v.id = ?) t", String.class, params.toArray())
                .stream().findFirst().map(json::lire);
    }

    /** Un bâtiment non archivé de même nom, dans la même commune, au même propriétaire : doublon d'import. */
    public Optional<Integer> parNom(String cle, int idCommune, int idProprietaire) {
        return jdbc.queryForList("""
                SELECT id FROM pat.batiment
                WHERE archive_le IS NULL AND id_commune = ? AND id_proprietaire = ?
                  AND btrim(regexp_replace(lower(unaccent(nom)), '[^a-z0-9]+', ' ', 'g')) = ?
                ORDER BY id LIMIT 1""", Integer.class, idCommune, idProprietaire, cle).stream().findFirst();
    }

    /** Les bâtiments non archivés de ce nom, toutes communes confondues : pour rattacher un lot importé. */
    public List<Integer> parNomSeul(String cle) {
        return jdbc.queryForList("""
                SELECT id FROM pat.batiment
                WHERE archive_le IS NULL AND btrim(regexp_replace(lower(unaccent(nom)), '[^a-z0-9]+', ' ', 'g')) = ?
                ORDER BY id""", Integer.class, cle);
    }

    public int creer(BatimentSaisieDto s, String validation, String origine, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO pat.batiment (nom, type, adresse, id_commune, id_quartier, geom, precision_gps, niveaux,
                    annee_construction, description, id_proprietaire, id_gestionnaire, id_suppleant, validation, origine, cree_par)
                VALUES (?, ?, ?, ?, ?,
                    CASE WHEN ?::float8 IS NULL OR ?::float8 IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326) END,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.nom(), s.type(), s.adresse(), s.idCommune(), s.idQuartier(),
                s.longitude(), s.latitude(), s.longitude(), s.latitude(),
                s.precisionGps(), s.niveaux(), s.anneeConstruction(), s.description(), s.idProprietaire(),
                s.idGestionnaire(), s.idSuppleant(), validation, origine, auteur);
        return id == null ? 0 : id;
    }

    public int modifier(BatimentSaisieDto s, String auteur) {
        return jdbc.update("""
                UPDATE pat.batiment SET nom = ?, type = ?, adresse = ?, id_commune = ?, id_quartier = ?,
                    geom = CASE WHEN ?::float8 IS NULL OR ?::float8 IS NULL THEN NULL ELSE ST_SetSRID(ST_MakePoint(?::float8, ?::float8), 4326) END,
                    precision_gps = ?, niveaux = ?, annee_construction = ?, description = ?, id_proprietaire = ?,
                    id_gestionnaire = ?, id_suppleant = ?, maj_le = now(), maj_par = ?
                WHERE id = ?""",
                s.nom(), s.type(), s.adresse(), s.idCommune(), s.idQuartier(),
                s.longitude(), s.latitude(), s.longitude(), s.latitude(),
                s.precisionGps(), s.niveaux(), s.anneeConstruction(), s.description(), s.idProprietaire(),
                s.idGestionnaire(), s.idSuppleant(), auteur, s.id());
    }

    public int valider(int id, String auteur) {
        return jdbc.update("UPDATE pat.batiment SET validation = 'VALIDE', maj_le = now(), maj_par = ? WHERE id = ? AND validation = 'A_VALIDER'",
                auteur, id);
    }

    public int archiver(int id, boolean archive, String auteur) {
        return jdbc.update("UPDATE pat.batiment SET archive_le = CASE WHEN ? THEN now() END, maj_le = now(), maj_par = ? WHERE id = ?",
                archive, auteur, id);
    }

    /** Les comptes à qui l'on peut confier un bâtiment : gestionnaires actifs. */
    public JsonNode gestionnaires() {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.nom), '[]')::text FROM (
                    SELECT id, nom, fonction FROM app.compte WHERE actif AND profil = 'GESTIONNAIRE') t""", String.class));
    }

    public boolean estGestionnaire(int idCompte) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM app.compte WHERE id = ? AND actif AND profil = 'GESTIONNAIRE'",
                Integer.class, idCompte);
        return n != null && n > 0;
    }

    /** L'historique d'un bâtiment et de ses lots, le plus récent d'abord. */
    public JsonNode historique(int id) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(to_json(j)), '[]')::text FROM (
                    SELECT id, auteur, objet, cle, action, avant, apres, le FROM app.journal
                    WHERE (objet = 'BATIMENT' AND cle = ?::text)
                       OR (objet = 'LOT' AND cle IN (SELECT l.id::text FROM pat.lot l WHERE l.id_batiment = ?))
                    ORDER BY le DESC, id DESC LIMIT 300) j""", String.class, id, id));
    }
}
