package com.eburtis.fact.assistant.outils;

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
 * Prépare une relance de loyer : rend les faits du bail, et le modèle rédige le message.
 * RIEN N'EST ENVOYÉ : la personne copie le texte si elle le veut. Un envoi automatique serait
 * une action « sortante », qui demanderait une validation.
 */
@Component
public class PreparerRelanceOutil implements OutilAssistant {

    private final LocationFacade locationFacade;

    public PreparerRelanceOutil(LocationFacade locationFacade) {
        this.locationFacade = locationFacade;
    }

    @Override
    public String nom() {
        return "preparer_relance";
    }

    @Override
    public String description() {
        return "Donne les faits d'un bail (occupant, lot, loyer, impayé, retard) pour rédiger un message de relance de loyer "
                + "courtois, à copier par la personne. N'envoie rien. Il faut l'id du bail : trouve-le d'abord avec lister_impayes.";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of(
                "type", "object",
                "properties", Map.of("id_bail", Map.of("type", "integer", "description", "L'id du bail.")),
                "required", List.of("id_bail"));
    }

    @Override
    public String droit() {
        return Profil.Droit.LOCATION_LIRE;
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        if (!arguments.hasNonNull("id_bail")) {
            return "Il manque id_bail. Cherche-le d'abord avec lister_impayes.";
        }
        // La facade vérifie que le bail est dans le périmètre du compte.
        Object bail = locationFacade.bail(arguments.get("id_bail").asInt(), utilisateur).get("bail");
        if (!(bail instanceof JsonNode b)) {
            return "Bail introuvable.";
        }
        if (b.path("moisRetard").asInt() <= 0) {
            return "Ce bail n'a pas de retard de paiement : une relance n'est pas justifiée. Dis-le à la personne.";
        }
        return "Faits du bail (rédige à partir d'eux seulement, sans rien inventer) :\n"
                + "- occupant : " + b.path("occupant").asText("") + "\n"
                + "- logement : lot " + b.path("numeroLot").asText("?") + ", " + b.path("batiment").asText("")
                + " (" + b.path("commune").asText("") + ")\n"
                + "- loyer mensuel : " + b.path("loyer").asText("?") + " FCFA, charges " + b.path("charges").asText("0") + " FCFA\n"
                + "- montant impayé : " + b.path("impaye").asText("0") + " FCFA\n"
                + "- retard : " + b.path("moisRetard").asInt() + " mois\n"
                + "- signataire : " + utilisateur.nom() + ", FACT Immobilier\n"
                + "Rédige un message court, poli et ferme, qui propose de prendre contact pour régulariser. "
                + "Précise à la personne que rien n'a été envoyé.";
    }
}
