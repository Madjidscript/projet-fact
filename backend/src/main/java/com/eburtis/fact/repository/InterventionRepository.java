package com.eburtis.fact.repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.presentation.dto.EtapeInterventionDto;
import com.eburtis.fact.presentation.dto.InterventionSaisieDto;
import com.eburtis.fact.presentation.dto.PrestataireSaisieDto;
import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Interventions, photos et prestataires (schéma tra). Les lectures reçoivent la condition de
 * périmètre du compte, posée sur pat.v_batiment (alias v). Attention aux blocs de texte : ils
 * suppriment l'espace en fin de ligne ; la condition est donc toujours collée par « \s ».
 */
@Repository
public class InterventionRepository {

    private static final String COLONNES = """
            i.id, i.reference, i.id_batiment AS "idBatiment", i.batiment, i.reference_batiment AS "referenceBatiment",
            i.commune, i.quartier, i.latitude, i.longitude, i.id_lot AS "idLot", i.numero_lot AS "numeroLot",
            i.id_gestionnaire AS "idGestionnaire", i.gestionnaire,
            i.categorie, (SELECT libelle FROM ref.valeur WHERE domaine = 'CATEGORIE_INTERVENTION' AND code = i.categorie) AS "libelleCategorie",
            i.urgence, (SELECT libelle FROM ref.valeur WHERE domaine = 'URGENCE' AND code = i.urgence) AS "libelleUrgence",
            i.titre, i.description, i.statut,
            (SELECT libelle FROM ref.valeur WHERE domaine = 'STATUT_INTERVENTION' AND code = i.statut) AS "libelleStatut",
            i.signale_le AS "signaleLe", i.signale_par AS "signalePar",
            i.id_prestataire AS "idPrestataire", i.prestataire, i.telephone_prestataire AS "telephonePrestataire",
            i.date_prevue AS "datePrevue", i.affecte_le AS "affecteLe", i.affecte_par AS "affectePar",
            i.constat, i.devis_montant AS "devisMontant", i.debute_le AS "debuteLe",
            i.accord_proprietaire AS "accordProprietaire", i.note_validation AS "noteValidation",
            i.accord_decision AS "accordDecision", i.accord_le AS "accordLe", i.accord_par AS "accordPar", i.accord_motif AS "accordMotif",
            tra.seuil_accord(i.id_batiment) AS "seuil",
            i.valide_le AS "valideLe", i.valide_par AS "validePar",
            i.cout_reel AS "coutReel", i.note_fin AS "noteFin", i.termine_le AS "termineLe", i.termine_par AS "terminePar",
            i.imputation, i.note_cloture AS "noteCloture", i.cloture_le AS "clotureLe", i.cloture_par AS "cloturePar",
            i.motif_annulation AS "motifAnnulation", i.annule_le AS "annuleLe", i.annule_par AS "annulePar",
            i.photos, i.delai_jours AS "delaiJours", i.ouverte""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public InterventionRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // ------------------------------------------------------------------ lecture
    /**
     * Les interventions du périmètre. {@code filtre} : « ouvertes », « closes », ou nul pour toutes ;
     * {@code idBatiment} restreint à un bâtiment.
     */
    public JsonNode lister(String perimetre, List<Object> paramsPerimetre, String filtre, Integer idBatiment) {
        List<Object> params = new ArrayList<>(paramsPerimetre);
        StringBuilder sql = new StringBuilder("SELECT coalesce(json_agg(t), '[]')::text FROM (SELECT ").append(COLONNES)
                .append(" FROM tra.v_intervention i JOIN pat.v_batiment v ON v.id = i.id_batiment WHERE ").append(perimetre);
        if ("ouvertes".equals(filtre)) {
            sql.append(" AND i.ouverte");
        } else if ("closes".equals(filtre)) {
            sql.append(" AND NOT i.ouverte");
        }
        if (idBatiment != null) {
            sql.append(" AND i.id_batiment = ?");
            params.add(idBatiment);
        }
        // Les plus pressantes d'abord : ouvertes, urgence, ancienneté.
        sql.append(" ORDER BY i.ouverte DESC, CASE i.urgence WHEN 'CRITIQUE' THEN 0 WHEN 'HAUTE' THEN 1 WHEN 'NORMALE' THEN 2 ELSE 3 END,")
                .append(" i.signale_le DESC LIMIT 500) t");
        return json.lire(jdbc.queryForObject(sql.toString(), String.class, params.toArray()));
    }

    public Optional<JsonNode> trouver(int id, String perimetre, List<Object> paramsPerimetre) {
        List<Object> params = new ArrayList<>(paramsPerimetre);
        params.add(id);
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + COLONNES
                + " FROM tra.v_intervention i JOIN pat.v_batiment v ON v.id = i.id_batiment WHERE " + perimetre + " AND i.id = ?) t",
                String.class, params.toArray()).stream().findFirst().map(json::lire);
    }

    /** Les chiffres des travaux du périmètre : ouvertes par étape, urgentes, délai moyen, coûts. */
    public JsonNode synthese(String perimetre, List<Object> paramsPerimetre) {
        return json.lire(jdbc.queryForObject("""
                SELECT json_build_object(
                    'parStatut', coalesce((SELECT json_object_agg(statut, n) FROM (SELECT i.statut, count(*) AS n FROM tra.v_intervention i
                                  JOIN pat.v_batiment v ON v.id = i.id_batiment WHERE\s""" + perimetre + """
                     GROUP BY i.statut) s), '{}'),
                    'urgentes', (SELECT count(*) FROM tra.v_intervention i JOIN pat.v_batiment v ON v.id = i.id_batiment
                                 WHERE i.ouverte AND i.urgence IN ('HAUTE', 'CRITIQUE') AND\s""" + perimetre + """
                    ),
                    'delaiMoyen', (SELECT round(avg(i.delai_jours), 1) FROM tra.v_intervention i JOIN pat.v_batiment v ON v.id = i.id_batiment
                                   WHERE i.cloture_le > now() - interval '12 months' AND\s""" + perimetre + """
                    ),
                    'coutMois', (SELECT coalesce(sum(i.cout_reel), 0) FROM tra.v_intervention i JOIN pat.v_batiment v ON v.id = i.id_batiment
                                 WHERE i.termine_le >= date_trunc('month', current_date) AND\s""" + perimetre + """
                    )
                )::text""", String.class, repeter(paramsPerimetre, 4)));
    }

    // ------------------------------------------------------------------ écriture
    public int signaler(InterventionSaisieDto s, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO tra.intervention (id_batiment, id_lot, categorie, urgence, titre, description, signale_par)
                VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                s.idBatiment(), s.idLot(), s.categorie(), s.urgence(), s.titre(), s.description(), auteur);
        return id == null ? 0 : id;
    }

    public void affecter(int id, EtapeInterventionDto e, String auteur) {
        jdbc.update("""
                UPDATE tra.intervention SET statut = 'AFFECTEE', id_prestataire = ?, date_prevue = ?, affecte_le = now(), affecte_par = ?,
                    maj_le = now()
                WHERE id = ?""", e.idPrestataire(), e.datePrevue(), auteur, id);
    }

    public void demarrer(int id, EtapeInterventionDto e, String auteur) {
        jdbc.update("""
                UPDATE tra.intervention SET statut = 'EN_COURS', constat = ?, devis_montant = ?, debute_le = now(), maj_le = now()
                WHERE id = ?""", e.constat(), e.devisMontant(), id);
    }

    public void valider(int id, boolean accordProprietaire, String note, String auteur) {
        jdbc.update("""
                UPDATE tra.intervention SET statut = 'VALIDEE', accord_proprietaire = ?, note_validation = ?, valide_le = now(),
                    valide_par = ?, maj_le = now()
                WHERE id = ?""", accordProprietaire, note, auteur, id);
    }

    public void terminer(int id, Integer coutReel, String note, String auteur) {
        jdbc.update("""
                UPDATE tra.intervention SET statut = 'TERMINEE', cout_reel = ?, note_fin = ?, termine_le = now(), termine_par = ?,
                    maj_le = now()
                WHERE id = ?""", coutReel, note, auteur, id);
    }

    public void cloturer(int id, String imputation, String note, String auteur) {
        jdbc.update("""
                UPDATE tra.intervention SET statut = 'CLOTUREE', imputation = ?, note_cloture = ?, cloture_le = now(), cloture_par = ?,
                    maj_le = now()
                WHERE id = ?""", imputation, note, auteur, id);
    }

    public void annuler(int id, String motif, String auteur) {
        jdbc.update("""
                UPDATE tra.intervention SET statut = 'ANNULEE', motif_annulation = ?, annule_le = now(), annule_par = ?, maj_le = now()
                WHERE id = ?""", motif, auteur, id);
    }

    // ------------------------------------------------------------------ photos
    public int ajouterPhoto(int idIntervention, String moment, String chemin, String nomOrigine, int largeur, int hauteur, int taille,
                            String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO tra.photo (id_intervention, moment, chemin, nom_origine, largeur, hauteur, taille, cree_par)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?) RETURNING id""", Integer.class,
                idIntervention, moment, chemin, nomOrigine, largeur, hauteur, taille, auteur);
        return id == null ? 0 : id;
    }

    public JsonNode photos(int idIntervention) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.moment, t.id), '[]')::text FROM (
                    SELECT id, moment, nom_origine AS "nomOrigine", largeur, hauteur, taille, cree_le AS "creeLe", cree_par AS "creePar"
                    FROM tra.photo WHERE id_intervention = ?) t""", String.class, idIntervention));
    }

    /** Le chemin d'une photo et l'intervention à laquelle elle appartient. */
    public Optional<Object[]> photo(int idPhoto) {
        return jdbc.query("SELECT chemin, id_intervention FROM tra.photo WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getInt(2)}, idPhoto).stream().findFirst();
    }

    public int nombrePhotos(int idIntervention, String moment) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM tra.photo WHERE id_intervention = ? AND moment = ?", Integer.class,
                idIntervention, moment);
        return n == null ? 0 : n;
    }

    /** Les modifications d'une intervention, la plus récente d'abord. */
    public JsonNode historique(int id) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(to_json(j) ORDER BY j.le DESC, j.id DESC), '[]')::text FROM (
                    SELECT id, auteur, action, le FROM app.journal WHERE objet = 'INTERVENTION' AND cle = ?::text) j""",
                String.class, String.valueOf(id)));
    }

    // ------------------------------------------------------------------ prestataires
    public JsonNode prestataires(boolean inactifs) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t.actif DESC, t.nom), '[]')::text FROM (
                    SELECT p.id, p.nom, p.specialite,
                           (SELECT libelle FROM ref.valeur WHERE domaine = 'CATEGORIE_INTERVENTION' AND code = p.specialite) AS "libelleSpecialite",
                           p.telephone, p.email, p.notes, p.actif,
                           (SELECT count(*) FROM tra.intervention i WHERE i.id_prestataire = p.id AND i.statut NOT IN ('CLOTUREE', 'ANNULEE')) AS "enCours"
                    FROM tra.prestataire p WHERE ? OR p.actif) t""", String.class, inactifs));
    }

    public Optional<JsonNode> prestataire(int id) {
        return jdbc.queryForList("""
                SELECT row_to_json(t)::text FROM (SELECT id, nom, specialite, telephone, email, notes, actif FROM tra.prestataire WHERE id = ?) t""",
                String.class, id).stream().findFirst().map(json::lire);
    }

    public int creerPrestataire(PrestataireSaisieDto s, String auteur) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO tra.prestataire (nom, specialite, telephone, email, notes, cree_par) VALUES (?, ?, ?, ?, ?, ?) RETURNING id""",
                Integer.class, s.nom(), s.specialite(), s.telephone(), s.email(), s.notes(), auteur);
        return id == null ? 0 : id;
    }

    public void modifierPrestataire(PrestataireSaisieDto s) {
        jdbc.update("UPDATE tra.prestataire SET nom = ?, specialite = ?, telephone = ?, email = ?, notes = ?, actif = ? WHERE id = ?",
                s.nom(), s.specialite(), s.telephone(), s.email(), s.notes(), s.actif() == null || s.actif(), s.id());
    }

    private static Object[] repeter(List<Object> params, int fois) {
        List<Object> r = new ArrayList<>();
        for (int i = 0; i < fois; i++) {
            r.addAll(params);
        }
        return r.toArray();
    }

    /** Le seuil de travaux du mandat en vigueur sur ce bâtiment, s'il en fixe un. */
    public java.util.Optional<Integer> seuilMandat(int idBatiment) {
        return jdbc.queryForList("SELECT seuil_travaux FROM pat.mandat WHERE id = pat.mandat_actif(?)", Integer.class, idBatiment)
                .stream().filter(java.util.Objects::nonNull).findFirst();
    }

    /** La réponse du propriétaire à un devis (§ 4.9) : ACCORD ou REFUS, avec son motif. */
    public int repondre(int id, String decision, String motif, String auteur) {
        return jdbc.update("""
                UPDATE tra.intervention SET accord_decision = ?, accord_motif = ?, accord_le = now(), accord_par = ?,
                    accord_proprietaire = (? = 'ACCORD')
                WHERE id = ? AND statut = 'EN_COURS' AND accord_decision IS NULL""", decision, motif, auteur, decision, id);
    }
}
