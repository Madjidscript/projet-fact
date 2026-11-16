package com.eburtis.fact.service;

import java.util.Locale;

import com.eburtis.fact.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Les messages adressés aux visiteurs du site : accusé de réception d'une demande (§ 3.3) et
 * alerte « nouveau bien » aux abonnés d'une recherche (§ 3.7). L'agence n'a pas encore de compte
 * d'envoi (e-mail, SMS, WhatsApp) : les messages sont préparés dans la boîte d'envoi
 * (web.message), où un agent les envoie d'un geste ; un envoi automatique s'y branchera.
 */
@Service
public class MessageService {

    private final JdbcTemplate jdbc;
    private final JsonUtils json;
    private final String site;

    public MessageService(JdbcTemplate jdbc, JsonUtils json, @Value("${fact.site.url}") String site) {
        this.jdbc = jdbc;
        this.json = json;
        this.site = site.replaceAll("/+$", "");
    }

    public String site() {
        return site;
    }

    /**
     * Le reçu d'un paiement encaissé (§ 4.6) : un message WhatsApp au téléphone du locataire, et un
     * e-mail s'il en a un, avec le lien qui ouvre sa quittance sans compte. Rend les canaux préparés.
     */
    public java.util.List<String> recu(JsonNode r) {
        String montant = String.format(Locale.FRANCE, "%,d", r.get("montant").asLong()).replace('\u202f', ' ').replace('\u00a0', ' ');
        String lien = site + "/api/public/recus/" + r.get("jeton").asText();
        String corps = "Bonjour " + r.get("occupant").asText() + ",\n\nFACT Immobilier a bien reçu votre paiement de " + montant
                + " FCFA pour le lot " + r.get("numeroLot").asText() + ", " + r.get("batiment").asText()
                + " (reçu " + r.get("numero").asText() + ").\nVotre reçu : " + lien
                + "\n\nMerci.\nFACT Immobilier · 27 22 46 91 38 · 05 55 43 55 55";
        String sujet = "Votre reçu " + r.get("numero").asText();
        java.util.List<String> canaux = new java.util.ArrayList<>();
        if (r.hasNonNull("telephone") && !r.get("telephone").asText().isBlank()) {
            jdbc.update("INSERT INTO web.message (canal, destinataire, sujet, corps, motif, id_encaissement) VALUES ('WHATSAPP', ?, ?, ?, 'RECU', ?)",
                    r.get("telephone").asText(), sujet, corps, r.get("id").asLong());
            canaux.add("WHATSAPP");
        }
        if (r.hasNonNull("email") && !r.get("email").asText().isBlank()) {
            jdbc.update("INSERT INTO web.message (canal, destinataire, sujet, corps, motif, id_encaissement) VALUES ('EMAIL', ?, ?, ?, 'RECU', ?)",
                    r.get("email").asText(), sujet, corps, r.get("id").asLong());
            canaux.add("EMAIL");
        }
        return canaux;
    }

    /** L'accusé de réception d'une demande, par e-mail si le visiteur en a donné un, sinon par WhatsApp. */
    public void accuser(int idDemande, String reference, String nom, String telephone, String email, String objet) {
        String corps = "Bonjour " + nom + ",\n\nFACT Immobilier a bien reçu votre " + objet + " (référence " + reference + ").\n"
                + "Un conseiller vous rappelle au " + telephone + " dans les meilleurs délais, aux heures d'ouverture.\n\n"
                + "Merci de votre confiance.\nFACT Immobilier · 27 22 46 91 38 · 05 55 43 55 55";
        boolean parEmail = email != null && !email.isBlank();
        jdbc.update("""
                INSERT INTO web.message (canal, destinataire, sujet, corps, motif, id_demande)
                VALUES (?, ?, ?, ?, 'ACCUSE', ?)""", parEmail ? "EMAIL" : "WHATSAPP", parEmail ? email : telephone,
                "Votre demande " + reference + " est bien reçue", corps, idDemande);
    }

    /**
     * Prévient les abonnés dont la recherche correspond à cette annonce, une fois par abonné et
     * par annonce. Rend le nombre de messages préparés.
     */
    public int alerterAbonnes(int idAnnonce) {
        JsonNode a = json.lire(jdbc.queryForObject("""
                SELECT row_to_json(t)::text FROM (
                    SELECT id, slug, titre, prix, operation, categorie, id_commune AS "idCommune", id_quartier AS "idQuartier",
                           pieces, surface, commune, quartier FROM web.v_annonce WHERE id = ?) t""", String.class, idAnnonce));
        String prix = String.format(Locale.FRANCE, "%,d", a.get("prix").asLong()).replace(' ', ' ').replace(' ', ' ')
                + ("LOCATION".equals(a.get("operation").asText()) ? " FCFA / mois" : " FCFA");
        String lieu = (a.hasNonNull("quartier") ? a.get("quartier").asText() + ", " : "") + a.get("commune").asText();
        String lien = site + "/biens/" + a.get("slug").asText();
        return jdbc.update("""
                INSERT INTO web.message (canal, destinataire, sujet, corps, motif, id_annonce)
                SELECT s.canal, s.contact, 'Nouveau bien : ' || ?,
                       'Bonjour,\n\nUn nouveau bien correspond à votre recherche sur le site de FACT Immobilier :\n'
                           || ? || '\n' || ? || ' · ' || ? || '\n' || ? || '\n\nPour ne plus recevoir ces alertes : '
                           || ? || '/alertes/arreter?jeton=' || s.jeton,
                       'ALERTE_RECHERCHE', ?
                FROM web.abonnement s
                WHERE s.desabonne_le IS NULL
                  AND coalesce(s.criteres->>'operation', ?) = ?
                  AND (NOT jsonb_exists(s.criteres, 'types') OR jsonb_array_length(s.criteres->'types') = 0
                       OR jsonb_exists(s.criteres->'types', ?))
                  AND (s.criteres->>'idCommune' IS NULL OR (s.criteres->>'idCommune')::int = ?)
                  AND (s.criteres->>'idQuartier' IS NULL OR (s.criteres->>'idQuartier')::int = ?::int)
                  AND (s.criteres->>'prixMin' IS NULL OR (s.criteres->>'prixMin')::bigint <= ?)
                  AND (s.criteres->>'prixMax' IS NULL OR (s.criteres->>'prixMax')::bigint >= ?)
                  AND (s.criteres->>'pieces' IS NULL OR coalesce(?::int, 0) >= (s.criteres->>'pieces')::int)
                  AND (s.criteres->>'surface' IS NULL OR coalesce(?::numeric, 0) >= (s.criteres->>'surface')::numeric)
                  AND NOT EXISTS (SELECT 1 FROM web.message m WHERE m.id_annonce = ? AND m.destinataire = s.contact
                                  AND m.motif = 'ALERTE_RECHERCHE')""",
                a.get("titre").asText(), a.get("titre").asText(), prix, lieu, lien, site, idAnnonce,
                a.get("operation").asText(), a.get("operation").asText(), a.get("categorie").asText(),
                a.get("idCommune").asInt(), a.hasNonNull("idQuartier") ? a.get("idQuartier").asInt() : null,
                a.get("prix").asLong(), a.get("prix").asLong(),
                a.hasNonNull("pieces") ? a.get("pieces").asInt() : null, a.hasNonNull("surface") ? a.get("surface").asDouble() : null,
                idAnnonce);
    }
}
