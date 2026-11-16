package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.presentation.dto.auth.ChangementMotDePasseDto;
import com.eburtis.fact.presentation.dto.auth.ConnexionDto;
import com.eburtis.fact.security.JwtTokenUtils;
import com.eburtis.fact.security.LimiteurConnexion;
import com.eburtis.fact.service.CompteService;
import com.eburtis.fact.service.JournalService;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Connexion, profil de la personne connectée et changement de son mot de passe. */
@Service
public class AuthFacade {

    private final CompteService compteService;
    private final JwtTokenUtils jwtTokenUtils;
    private final LimiteurConnexion limiteurConnexion;
    private final JournalService journalService;

    public AuthFacade(CompteService compteService, JwtTokenUtils jwtTokenUtils, LimiteurConnexion limiteurConnexion,
                      JournalService journalService) {
        this.compteService = compteService;
        this.jwtTokenUtils = jwtTokenUtils;
        this.limiteurConnexion = limiteurConnexion;
        this.journalService = journalService;
    }

    /** Ouvre une session depuis le poste {@code poste} (adresse de la requête). */
    public Map<String, Object> connexion(ConnexionDto c, String poste) {
        if (limiteurConnexion.bloque(poste)) {
            throw new ErreurMetier(HttpStatus.TOO_MANY_REQUESTS, "Trop d'essais manqués : réessayez dans cinq minutes.");
        }
        Utilisateur u = compteService.verifier(c.identifiant(), c.motDePasse()).orElse(null);
        if (u == null) {
            limiteurConnexion.echec(poste);
            // Même message pour un identifiant inconnu et un mot de passe faux : on ne dit pas lequel.
            throw new ErreurMetier(HttpStatus.UNAUTHORIZED, "Identifiant ou mot de passe incorrect.");
        }
        limiteurConnexion.reussite(poste);
        journalService.consigner(u.signature(), JournalService.CONNEXION, u.identifiant(), "Connexion");
        return session(u);
    }

    public Map<String, Object> moi(Utilisateur u) {
        return profil(u);
    }

    public Map<String, Object> changerMotDePasse(Utilisateur courant, ChangementMotDePasseDto c) {
        Utilisateur u = compteService.changerMotDePasse(courant, c.actuel(), c.nouveau());
        return session(u);        // l'ancien jeton tombe : un nouveau est remis aussitôt
    }

    private Map<String, Object> session(Utilisateur u) {
        JwtTokenUtils.Jeton j = jwtTokenUtils.emettre(u.identifiant(), u.versionSession());
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jeton", j.valeur());
        r.put("expiration", j.expiration().toString());
        r.put("compte", profil(u));
        return r;
    }

    /** Ce que l'interface sait de la personne connectée : de quoi composer son menu, rien de plus. */
    private Map<String, Object> profil(Utilisateur u) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("identifiant", u.identifiant());
        r.put("nom", u.nom());
        r.put("fonction", u.fonction());
        r.put("email", u.email());
        r.put("profil", u.profil().name());
        r.put("libelleProfil", u.profil().libelle());
        r.put("portee", u.profil().portee().name());
        r.put("droits", List.copyOf(u.droits()));
        r.put("doitChanger", u.doitChanger());
        return r;
    }
}
