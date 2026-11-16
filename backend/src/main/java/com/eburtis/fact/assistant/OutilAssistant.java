package com.eburtis.fact.assistant;

import java.util.Map;

import com.eburtis.fact.domain.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * UN OUTIL : une action que le modèle a le droit de DEMANDER. Il ne l'exécute jamais lui-même :
 * c'est la boucle (BoucleAgent) qui appelle {@link #executer}, avec le compte connecté.
 *
 * Le modèle ne voit que trois choses : {@link #nom}, {@link #description} et {@link #parametres}.
 * Il ne voit jamais le code Java. La description est donc un prompt : c'est elle qui lui fait
 * choisir le bon outil au bon moment.
 *
 * Deux sortes d'outils :
 *   - LECTURE (par défaut) : la boucle appelle {@link #executer} tout de suite ;
 *   - ÉCRITURE ({@link #demandeValidation} vrai) : la boucle appelle seulement {@link #preparer},
 *     qui vérifie et décrit l'action. Une carte « Valider / Annuler » s'affiche ; {@link #executer}
 *     n'est appelé qu'après le clic de la personne.
 *
 * Pour ajouter un outil : une classe @Component dans assistant/outils/ qui implémente cette
 * interface. La boucle la trouve toute seule.
 */
public interface OutilAssistant {

    /** Le nom que le modèle utilise pour demander l'outil, par exemple « chercher_batiments ». */
    String nom();

    /** Quand utiliser l'outil, en une ou deux phrases claires. C'est ce que le modèle lit pour choisir. */
    String description();

    /** Les paramètres que le modèle doit remplir, au format JSON Schema. */
    Map<String, Object> parametres();

    /**
     * Le droit FACT qu'il faut porter pour utiliser l'outil (Profil.Droit), ou null si tout compte connecté peut.
     * Un compte sans ce droit ne reçoit même pas l'outil dans la liste envoyée au modèle.
     */
    String droit();

    /**
     * L'action réelle. Le texte renvoyé est ce que le modèle « voit » comme résultat : clair et factuel.
     *
     * @param arguments   ce que le modèle a rempli (à vérifier : il peut se tromper)
     * @param utilisateur le compte connecté, venu du jeton JWT, JAMAIS du modèle
     * @param actions     les consignes à laisser pour Angular (ouvrir une page...) ; la plupart des outils l'ignorent
     */
    String executer(JsonNode arguments, Utilisateur utilisateur, ActionsPage actions);

    /** Vrai pour un outil qui modifie des données : rien n'est fait avant le clic « Valider ». */
    default boolean demandeValidation() {
        return false;
    }

    /**
     * Pour un outil à validation : vérifie les paramètres SANS RIEN MODIFIER, et rend la description
     * exacte de ce qui sera fait, affichée sur la carte. Lève une ErreurMetier si l'action est impossible.
     */
    default String preparer(JsonNode arguments, Utilisateur utilisateur) {
        throw new UnsupportedOperationException(nom() + " ne demande pas de validation.");
    }
}
