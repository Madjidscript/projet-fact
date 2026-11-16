package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.facade.AuthFacade;
import com.eburtis.fact.presentation.dto.auth.ChangementMotDePasseDto;
import com.eburtis.fact.presentation.dto.auth.ConnexionDto;
import com.eburtis.fact.security.SecurityUtils;
import jakarta.servlet.http.HttpServletRequest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Connexion, profil de la personne connectée, changement de son mot de passe. */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthFacade authFacade;

    public AuthController(AuthFacade authFacade) {
        this.authFacade = authFacade;
    }

    @PostMapping("/connexion")
    public Map<String, Object> connexion(@RequestBody ConnexionDto dto, HttpServletRequest requete) {
        return authFacade.connexion(dto, requete.getRemoteAddr());
    }

    @GetMapping("/moi")
    public Map<String, Object> moi() {
        return authFacade.moi(SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/mot-de-passe")
    public Map<String, Object> changerMotDePasse(@RequestBody ChangementMotDePasseDto dto) {
        return authFacade.changerMotDePasse(SecurityUtils.utilisateurConnecte(), dto);
    }
}
