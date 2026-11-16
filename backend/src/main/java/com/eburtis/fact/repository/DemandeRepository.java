package com.eburtis.fact.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Les demandes venues du site (web.demande), leurs pièces jointes et la boîte d'envoi (web.message). */
@Repository
public class DemandeRepository {

    private static final String CHAMPS = """
            d.id, d.reference, d.type, (SELECT libelle FROM ref.valeur WHERE domaine = 'TYPE_DEMANDE' AND code = d.type) AS "libelleType",
            d.statut, (SELECT libelle FROM ref.valeur WHERE domaine = 'STATUT_DEMANDE' AND code = d.statut) AS "libelleStatut",
            d.id_annonce AS "idAnnonce", a.titre AS annonce, a.slug, a.reference AS "referenceAnnonce",
            d.id_batiment AS "idBatiment", b.nom AS batiment, d.id_compte AS "idCompte", c.nom AS "traitePar",
            d.nom, d.telephone, d.email, d.date_souhaitee AS "dateSouhaitee", d.creneau, d.message, d.details,
            d.latitude, d.longitude, d.consentement, d.note, d.rdv_le AS "rdvLe", d.cree_le AS "creeLe", d.maj_le AS "majLe",
            d.maj_par AS "majPar", (SELECT count(*) FROM web.demande_piece p WHERE p.id_demande = d.id) AS pieces,
            CASE WHEN d.statut = 'NOUVELLE' THEN web.heures_ouvrees(d.cree_le) > 24 ELSE false END AS "enRetard\"""";

    private static final String FROM = """
             FROM web.demande d
             LEFT JOIN web.annonce a ON a.id = d.id_annonce
             LEFT JOIN pat.batiment b ON b.id = d.id_batiment
             LEFT JOIN app.compte c ON c.id = d.id_compte""";

    private final JdbcTemplate jdbc;
    private final JsonUtils json;

    public DemandeRepository(JdbcTemplate jdbc, JsonUtils json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public int creer(String type, Integer idAnnonce, Integer idBatiment, Integer idCompte, String nom, String telephone, String email,
                     LocalDate date, String creneau, String message, String details, Double latitude, Double longitude, boolean consentement) {
        Integer id = jdbc.queryForObject("""
                INSERT INTO web.demande (type, id_annonce, id_batiment, id_compte, nom, telephone, email, date_souhaitee, creneau,
                    message, details, latitude, longitude, consentement)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?) RETURNING id""", Integer.class,
                type, idAnnonce, idBatiment, idCompte, nom, telephone, email, date, creneau, message, details, latitude, longitude, consentement);
        return id == null ? 0 : id;
    }

    public String reference(int id) {
        return jdbc.queryForObject("SELECT reference FROM web.demande WHERE id = ?", String.class, id);
    }

    public void ajouterPiece(int idDemande, String chemin, String nom, String type, int taille) {
        jdbc.update("INSERT INTO web.demande_piece (id_demande, chemin, nom, type, taille) VALUES (?, ?, ?, ?, ?)",
                idDemande, chemin, nom, type, taille);
    }

    /**
     * Les demandes visibles du compte : {@code tout} pour la Direction et l'éditeur web ; sinon celles qui
     * lui sont confiées ou qui portent sur un bâtiment de son périmètre.
     */
    public JsonNode lister(boolean tout, int idCompte, String perimetre, List<Object> paramsPerimetre) {
        List<Object> p = new ArrayList<>();
        String condition = "true";
        if (!tout) {
            p.add(idCompte);
            p.addAll(paramsPerimetre);
            condition = "(d.id_compte = ? OR EXISTS (SELECT 1 FROM pat.v_batiment v WHERE v.id = d.id_batiment AND " + perimetre + "))";
        }
        return json.lire(jdbc.queryForObject("SELECT coalesce(json_agg(t ORDER BY (t.statut = 'NOUVELLE') DESC, t.\"creeLe\" DESC), '[]')::text FROM (SELECT "
                + CHAMPS + FROM + " WHERE " + condition + " AND d.cree_le > now() - interval '18 months') t", String.class, p.toArray()));
    }

    public Optional<JsonNode> trouver(int id) {
        return jdbc.queryForList("SELECT row_to_json(t)::text FROM (SELECT " + CHAMPS + FROM + " WHERE d.id = ?) t", String.class, id)
                .stream().findFirst().map(json::lire);
    }

    public JsonNode pieces(int idDemande) {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(json_build_object('id', id, 'nom', nom, 'type', type, 'taille', taille) ORDER BY id), '[]')::text
                FROM web.demande_piece WHERE id_demande = ?""", String.class, idDemande));
    }

    public Optional<Object[]> piece(int idPiece) {
        return jdbc.query("SELECT chemin, nom, type, id_demande FROM web.demande_piece WHERE id = ?",
                (rs, i) -> new Object[]{rs.getString(1), rs.getString(2), rs.getString(3), rs.getInt(4)}, idPiece).stream().findFirst();
    }

    public void suivre(int id, String statut, String note, Integer idCompte, java.time.OffsetDateTime rdv, String auteur) {
        jdbc.update("""
                UPDATE web.demande SET statut = ?, note = ?, id_compte = ?, rdv_le = ?, maj_le = now(), maj_par = ? WHERE id = ?""",
                statut, note, idCompte, rdv, auteur, id);
    }

    // ------------------------------------------------------------------ boîte d'envoi
    public JsonNode messagesEnAttente() {
        return json.lire(jdbc.queryForObject("""
                SELECT coalesce(json_agg(t ORDER BY t."creeLe"), '[]')::text FROM (
                    SELECT m.id, m.canal, m.destinataire, m.sujet, m.corps, m.motif, m.id_demande AS "idDemande",
                           d.reference AS "referenceDemande", m.cree_le AS "creeLe"
                    FROM web.message m LEFT JOIN web.demande d ON d.id = m.id_demande
                    WHERE m.envoye_le IS NULL AND m.abandonne_le IS NULL LIMIT 300) t""", String.class));
    }

    public int compterMessagesEnAttente() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM web.message WHERE envoye_le IS NULL AND abandonne_le IS NULL", Integer.class);
        return n == null ? 0 : n;
    }

    public int marquerMessage(int id, boolean envoye, String auteur) {
        return jdbc.update(envoye
                ? "UPDATE web.message SET envoye_le = now(), envoye_par = ? WHERE id = ? AND envoye_le IS NULL AND abandonne_le IS NULL"
                : "UPDATE web.message SET abandonne_le = now(), envoye_par = ? WHERE id = ? AND envoye_le IS NULL AND abandonne_le IS NULL",
                auteur, id);
    }

    // ------------------------------------------------------------------ abonnements
    public void abonner(String contact, String canal, String criteres, String jeton) {
        jdbc.update("INSERT INTO web.abonnement (contact, canal, criteres, jeton) VALUES (?, ?, ?::jsonb, ?)", contact, canal, criteres, jeton);
    }

    public int abonnementsActifs(String contact) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM web.abonnement WHERE contact = ? AND desabonne_le IS NULL", Integer.class, contact);
        return n == null ? 0 : n;
    }

    public int desabonner(String jeton) {
        return jdbc.update("UPDATE web.abonnement SET desabonne_le = now() WHERE jeton = ? AND desabonne_le IS NULL", jeton);
    }
}
