package com.eburtis.fact.security;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** Accès à la personne connectée depuis les contrôleurs et les services. */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static Utilisateur utilisateurConnecte() {
        Authentication a = SecurityContextHolder.getContext().getAuthentication();
        if (a == null || !(a.getPrincipal() instanceof Utilisateur u)) {
            throw new ErreurMetier(HttpStatus.UNAUTHORIZED, "Connexion requise.");
        }
        return u;
    }

    public static Utilisateur exigerDroit(String droit) {
        Utilisateur u = utilisateurConnecte();
        if (!u.peut(droit)) {
            throw ErreurMetier.refuse("Votre compte n'a pas ce droit.");
        }
        return u;
    }
}
