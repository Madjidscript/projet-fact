package com.eburtis.fact.assistant;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * LES CONSIGNES POUR ANGULAR, le temps d'une question.
 *
 * Le serveur ne peut pas cliquer dans le navigateur. Un outil qui veut « ouvrir une page » ou
 * « demander une validation » laisse donc une consigne ici ; elle part avec la réponse
 * ({"reponse": ..., "actions": [...]}), et c'est le robot, côté Angular, qui l'exécute.
 */
public class ActionsPage {

    private final List<Map<String, Object>> actions = new ArrayList<>();

    /** Demande à Angular d'ouvrir cette page. {@code libelle} s'affiche sous la réponse, en lien. */
    public void naviguer(String chemin, String libelle) {
        actions.add(Map.of("type", "naviguer", "chemin", chemin, "libelle", libelle));
    }

    /** Demande à Angular d'afficher une carte « Valider / Annuler » pour cette action en attente. */
    public void validation(long id, String resume, Instant expireLe) {
        actions.add(Map.of("type", "validation", "id", id, "resume", resume, "expireLe", expireLe.toString()));
    }

    public List<Map<String, Object>> toutes() {
        return List.copyOf(actions);
    }
}
