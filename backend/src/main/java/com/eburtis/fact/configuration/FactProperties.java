package com.eburtis.fact.configuration;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Les réglages de l'application, préfixés « fact. » dans application.properties.
 * Chaque valeur se surcharge par une variable d'environnement (voir le fichier).
 */
@ConfigurationProperties(prefix = "fact")
public record FactProperties(Jwt jwt, Comptes comptes, Cors cors, Carte carte) {

    /** Jetons de session : le secret doit faire au moins 32 caractères en production. */
    public record Jwt(String secret, int dureeHeures) {
    }

    /**
     * Compte de départ : {@code motDePasseInitial} vide, il reçoit un mot de passe provisoire
     * aléatoire, écrit dans {@code fichierProvisoires}.
     */
    public record Comptes(String motDePasseInitial, String fichierProvisoires) {
    }

    /** Origines autorisées à appeler l'API depuis un autre domaine (ng serve en développement). */
    public record Cors(List<String> origines) {
    }

    /** Cadrage de départ de la carte : Abidjan. */
    public record Carte(double latitude, double longitude, int zoom) {
    }
}
