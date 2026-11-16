package com.eburtis.fact.assistant.outils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.assistant.ActionsPage;
import com.eburtis.fact.assistant.OutilAssistant;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.InterventionFacade;
import com.eburtis.fact.presentation.dto.InterventionSaisieDto;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/**
 * PREMIER OUTIL D'ÉCRITURE : signaler une panne (créer une intervention).
 *
 * Il demande une validation : la boucle appelle seulement {@link #preparer}, qui vérifie tout
 * sans rien écrire. La carte « Valider / Annuler » s'affiche ; {@link #executer} ne tourne
 * qu'au clic, et passe par InterventionFacade.signaler, avec toutes ses règles et son journal.
 */
@Component
public class SignalerInterventionOutil implements OutilAssistant {

    /** Recopiées de la table ref.valeur (domaines CATEGORIE_INTERVENTION et URGENCE, migration V1). */
    private static final Map<String, String> CATEGORIES = new LinkedHashMap<>();
    private static final Map<String, String> URGENCES = new LinkedHashMap<>();

    static {
        CATEGORIES.put("PLOMBERIE", "Plomberie");
        CATEGORIES.put("ELECTRICITE", "Électricité");
        CATEGORIES.put("PEINTURE", "Peinture");
        CATEGORIES.put("MACONNERIE", "Maçonnerie");
        CATEGORIES.put("MENUISERIE", "Menuiserie");
        CATEGORIES.put("ETANCHEITE", "Étanchéité / toiture");
        CATEGORIES.put("CLIMATISATION", "Climatisation");
        CATEGORIES.put("ASSAINISSEMENT", "Assainissement");
        CATEGORIES.put("NETTOYAGE", "Nettoyage / enlèvement");
        CATEGORIES.put("AUTRE", "Autre");
        URGENCES.put("BASSE", "Peut attendre");
        URGENCES.put("NORMALE", "Normale");
        URGENCES.put("HAUTE", "Urgente");
        URGENCES.put("CRITIQUE", "Critique (sécurité, dégât d'eau)");
    }

    private final BatimentFacade batimentFacade;
    private final InterventionFacade interventionFacade;

    public SignalerInterventionOutil(BatimentFacade batimentFacade, InterventionFacade interventionFacade) {
        this.batimentFacade = batimentFacade;
        this.interventionFacade = interventionFacade;
    }

    @Override
    public String nom() {
        return "signaler_intervention";
    }

    @Override
    public String description() {
        return "Prépare le signalement d'une panne ou d'un besoin de travaux sur un bâtiment (crée une intervention). "
                + "La personne devra valider avant que ce soit enregistré. Il faut l'id du bâtiment : trouve-le d'abord avec "
                + "chercher_batiments. Si la catégorie ou l'urgence ne sont pas claires, demande-les avant.";
    }

    @Override
    public Map<String, Object> parametres() {
        return Map.of(
                "type", "object",
                "properties", Map.of(
                        "id_batiment", Map.of("type", "integer", "description", "L'id du bâtiment concerné."),
                        "categorie", Map.of("type", "string", "enum", List.copyOf(CATEGORIES.keySet()), "description", "Le type de travaux."),
                        "urgence", Map.of("type", "string", "enum", List.copyOf(URGENCES.keySet()), "description", "L'urgence. NORMALE par défaut."),
                        "titre", Map.of("type", "string", "description", "La panne en quelques mots, par exemple « Fuite sous l'évier ». 160 caractères au plus."),
                        "description", Map.of("type", "string", "description", "Les détails utiles au prestataire, si la personne en a donné.")),
                "required", List.of("id_batiment", "categorie", "titre"));
    }

    @Override
    public String droit() {
        return Profil.Droit.TRAVAUX_ECRIRE;
    }

    @Override
    public boolean demandeValidation() {
        return true;
    }

    /** Vérifie tout, n'écrit rien, et décrit exactement ce qui sera créé. */
    @Override
    public String preparer(JsonNode arguments, Utilisateur utilisateur) {
        Saisie s = lire(arguments);
        // La facade vérifie que le bâtiment est dans le périmètre du compte (sinon : ErreurMetier, donc « Refusé »).
        JsonNode batiment = (JsonNode) batimentFacade.fiche(s.idBatiment(), utilisateur).get("batiment");
        if (!batiment.path("archiveLe").isNull() && !batiment.path("archiveLe").isMissingNode()) {
            throw ErreurMetier.invalide("Ce bâtiment est archivé : on ne peut plus y signaler de panne.");
        }
        return "Signaler une intervention sur « " + batiment.path("nom").asText() + " » (" + batiment.path("reference").asText() + ")\n"
                + "Catégorie : " + CATEGORIES.get(s.categorie()) + " · Urgence : " + URGENCES.get(s.urgence()) + "\n"
                + "Titre : " + s.titre()
                + (s.description() == null ? "" : "\nDétails : " + s.description());
    }

    /** Ne tourne qu'après le clic « Valider », avec les paramètres exacts qui ont été montrés. */
    @Override
    public String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions) {
        Saisie s = lire(arguments);
        JsonNode cree = interventionFacade.signaler(
                new InterventionSaisieDto(s.idBatiment(), null, s.categorie(), s.urgence(), s.titre(), s.description()), utilisateur);
        int id = cree.path("id").asInt();
        String reference = cree.path("reference").asText("n° " + id);
        actions.naviguer("/travaux?intervention=" + id, "Intervention " + reference);
        return "Intervention " + reference + " créée : « " + s.titre() + " ».";
    }

    private record Saisie(int idBatiment, String categorie, String urgence, String titre, String description) {
    }

    /** Le modèle peut se tromper : on vérifie chaque paramètre ici, jamais seulement dans la description. */
    private static Saisie lire(JsonNode a) {
        if (!a.hasNonNull("id_batiment")) {
            throw ErreurMetier.invalide("Il manque id_batiment : cherche d'abord le bâtiment avec chercher_batiments.");
        }
        String categorie = a.path("categorie").asText("");
        if (!CATEGORIES.containsKey(categorie)) {
            throw ErreurMetier.invalide("Catégorie inconnue. Possibles : " + String.join(", ", CATEGORIES.keySet()) + ".");
        }
        String urgence = a.path("urgence").asText("NORMALE");
        if (urgence.isBlank()) {
            urgence = "NORMALE";
        }
        if (!URGENCES.containsKey(urgence)) {
            throw ErreurMetier.invalide("Urgence inconnue. Possibles : " + String.join(", ", URGENCES.keySet()) + ".");
        }
        String titre = a.path("titre").asText("").trim();
        if (titre.isEmpty() || titre.length() > 160) {
            throw ErreurMetier.invalide("Le titre est obligatoire et fait 160 caractères au plus.");
        }
        String description = a.path("description").asText("").trim();
        return new Saisie(a.get("id_batiment").asInt(), categorie, urgence, titre,
                description.isEmpty() ? null : description.substring(0, Math.min(description.length(), 2000)));
    }
}
