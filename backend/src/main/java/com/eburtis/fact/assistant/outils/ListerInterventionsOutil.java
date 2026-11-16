package com.eburtis.fact.assistant.outils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.facade.InterventionFacade;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/** Les interventions (travaux) encore ouvertes, les plus pressantes d'abord. */
@Component
public class ListerInterventionsOutil implements OutilAssistant {

    private static final int MAX_RESULTATS = 25;

    private final InterventionFacade interventionFacade;

    public ListerInterventionsOutil(InterventionFacade interventionFacade) {
        this.interventionFacade = interventionFacade;
    }

    @Override
    public String nom() {
        return "lister_interventions";
    }

    @Override
    public String description() {
        return "Liste les interventions de travaux encore ouvertes (pannes signalées, en cours), avec leur urgence et leur statut. "
                + "À utiliser pour les questions sur les travaux, les pannes, les urgences ou les prestataires.";
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
        return Profil.Droit.TRAVAUX_LIRE;
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        Integer idBatiment = arguments.hasNonNull("id_batiment") ? arguments.get("id_batiment").asInt() : null;
        JsonNode interventions = interventionFacade.lister(utilisateur, "ouvertes", idBatiment);
        if (interventions.isEmpty()) {
            return "Aucune intervention ouverte dans le périmètre de ce compte.";
        }
        List<String> lignes = new ArrayList<>();
        for (JsonNode i : interventions) {
            if (lignes.size() >= MAX_RESULTATS) {
                break;
            }
            lignes.add("- " + i.path("reference").asText("") + " · " + i.path("batiment").asText("")
                    + " · " + i.path("libelleCategorie").asText(i.path("categorie").asText(""))
                    + " · urgence " + i.path("libelleUrgence").asText(i.path("urgence").asText(""))
                    + " · " + i.path("libelleStatut").asText(i.path("statut").asText(""))
                    + " · « " + i.path("titre").asText("") + " »"
                    + " · signalée le " + i.path("signaleLe").asText("").replaceFirst("T.*", ""));
        }
        return interventions.size() + " intervention(s) ouverte(s)"
                + (interventions.size() > MAX_RESULTATS ? ", les " + MAX_RESULTATS + " plus pressantes ci-dessous" : "") + " :\n"
                + String.join("\n", lignes);
    }
}
