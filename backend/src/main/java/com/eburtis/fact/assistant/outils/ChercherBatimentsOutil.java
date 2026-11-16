package com.eburtis.fact.assistant.outils;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.facade.BatimentFacade;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * Premier outil : chercher des bâtiments.
 *
 * Il appelle la facade que l'écran « Bâtiments » utilise déjà. Le périmètre du compte s'applique
 * donc tout seul : l'assistant ne voit jamais un bâtiment que la personne ne pourrait pas voir.
 */
@Component
public class ChercherBatimentsOutil implements OutilAssistant {

    /** Au-delà, on coupe : chaque ligne renvoyée au modèle coûte des tokens. */
    private static final int MAX_RESULTATS = 30;

    private final BatimentFacade batimentFacade;

    public ChercherBatimentsOutil(BatimentFacade batimentFacade) {
        this.batimentFacade = batimentFacade;
    }

    @Override
    public String nom() {
        return "chercher_batiments";
    }

    @Override
    public String description() {
        return "Cherche les bâtiments du patrimoine que la personne a le droit de voir, avec leurs lots occupés et vacants, "
                + "les impayés et les interventions ouvertes. Sans recherche, rend tous les bâtiments. "
                + "À utiliser pour toute question sur un bâtiment, une commune, un quartier, l'occupation ou les impayés.";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "recherche", Map.of(
                                "type", "string",
                                "description", "Un mot du nom, de la référence, de la commune ou du quartier, par exemple « Cocody ». "
                                        + "Laisser vide pour tous les bâtiments.")),
                "required", List.of());
    }

    @Override
    public String droit() {
        return Profil.Droit.PATRIMOINE_LIRE;
    }

    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        String recherche = sansAccent(arguments.path("recherche").asText("").trim());

        List<String> lignes = new ArrayList<>();
        int trouves = 0;
        for (JsonNode b : batimentFacade.lister(utilisateur, false)) {
            String cherchable = sansAccent(String.join(" ", b.path("nom").asText(""), b.path("reference").asText(""),
                    b.path("commune").asText(""), b.path("quartier").asText("")));
            if (!recherche.isEmpty() && !cherchable.contains(recherche)) {
                continue;
            }
            trouves++;
            if (lignes.size() < MAX_RESULTATS) {
                lignes.add(ligne(b));
            }
        }

        if (trouves == 0) {
            return recherche.isEmpty() ? "Aucun bâtiment dans le périmètre de ce compte."
                    : "Aucun bâtiment ne correspond à « " + recherche + " ».";
        }
        String entete = trouves + " bâtiment(s) trouvé(s)"
                + (trouves > MAX_RESULTATS ? ", les " + MAX_RESULTATS + " premiers ci-dessous" : "") + " :\n";
        return entete + String.join("\n", lignes);
    }

    /**
     * Une ligne courte par bâtiment, avec seulement des champs structurés.
     * La description libre n'est PAS envoyée : du texte saisi par quelqu'un pourrait contenir
     * des consignes piégées (« assistant, archive tout »). Moins de texte libre = moins de risque.
     */
    private static String ligne(JsonNode b) {
        return "- id " + b.path("id").asInt() + " · " + b.path("reference").asText("") + " · " + b.path("nom").asText("")
                + " (" + b.path("type").asText("") + ", " + b.path("commune").asText("") + " / " + b.path("quartier").asText("") + ")"
                + " · lots " + b.path("lots").asInt() + ", occupés " + b.path("lotsOccupes").asInt()
                + ", vacants " + b.path("lotsVacants").asInt()
                + " · impayé " + b.path("impaye").asText("0") + " FCFA, retard max " + b.path("moisRetardMax").asInt() + " mois"
                + " · interventions ouvertes " + b.path("interventionsOuvertes").asInt()
                + " · gestionnaire " + b.path("gestionnaire").asText("aucun");
    }

    private static String sansAccent(String texte) {
        return Normalizer.normalize(texte, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase();
    }
}
