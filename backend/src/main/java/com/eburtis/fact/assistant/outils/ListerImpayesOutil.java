package com.eburtis.fact.assistant.outils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.facade.LocationFacade;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Les baux en retard de paiement, du périmètre du compte.
 *
 * Données personnelles : le nom de l'occupant est envoyé au modèle (il en a besoin pour répondre
 * « qui doit combien »), jamais son téléphone ni son adresse e-mail.
 */
@Component
public class ListerImpayesOutil implements OutilAssistant {

    private static final int MAX_RESULTATS = 30;

    private final LocationFacade locationFacade;

    public ListerImpayesOutil(LocationFacade locationFacade) {
        this.locationFacade = locationFacade;
    }

    @Override
    public String nom() {
        return "lister_impayes";
    }

    @Override
    public String description() {
        return "Liste les baux en retard de paiement : occupant, lot, bâtiment, loyer, montant impayé et nombre de mois de retard. "
                + "À utiliser pour les questions sur les impayés, les retards, qui doit combien, ou avant de préparer une relance.";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id_batiment", Map.of("type", "integer", "description", "Pour un seul bâtiment : son id. Sinon, ne pas le donner.")),
                "required", List.of());
    }

    @Override
    public String droit() {
        return Profil.Droit.LOCATION_LIRE;
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        Integer idBatiment = arguments.hasNonNull("id_batiment") ? arguments.get("id_batiment").asInt() : null;
        JsonNode baux = locationFacade.baux(utilisateur, "retard", idBatiment);
        if (baux.isEmpty()) {
            return "Aucun bail en retard de paiement dans le périmètre de ce compte.";
        }
        List<String> lignes = new ArrayList<>();
        long total = 0;
        for (JsonNode b : baux) {
            total += b.path("impaye").asLong();
            if (lignes.size() < MAX_RESULTATS) {
                lignes.add("- bail id " + b.path("id").asInt() + " · " + b.path("occupant").asText("occupant inconnu")
                        + " · lot " + b.path("numeroLot").asText("?") + ", " + b.path("batiment").asText("")
                        + " (bâtiment id " + b.path("idBatiment").asInt() + ")"
                        + " · loyer " + b.path("loyer").asText("?") + " FCFA"
                        + " · impayé " + b.path("impaye").asText("0") + " FCFA"
                        + " · " + b.path("moisRetard").asInt() + " mois de retard");
            }
        }
        return baux.size() + " bail(s) en retard, " + total + " FCFA impayés au total"
                + (baux.size() > MAX_RESULTATS ? " (les " + MAX_RESULTATS + " plus gros ci-dessous)" : "") + " :\n"
                + String.join("\n", lignes);
    }
}
