package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.AffectationFacade;
import com.eburtis.fact.presentation.dto.ReaffectationDto;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Qui gère quoi (§ 4.3) : portefeuilles, réaffectation et historique. */
@RestController
@RequestMapping("/api/affectations")
public class AffectationController {

    private final AffectationFacade affectationFacade;

    public AffectationController(AffectationFacade affectationFacade) {
        this.affectationFacade = affectationFacade;
    }

    @GetMapping("/portefeuilles")
    public Map<String, Object> portefeuilles() {
        return affectationFacade.portefeuilles(SecurityUtils.exigerDroit(Profil.Droit.AFFECTATION_LIRE));
    }

    @GetMapping("/historique")
    public Object historique(@RequestParam(required = false) Integer batiment) {
        SecurityUtils.exigerDroit(Profil.Droit.AFFECTATION_LIRE);
        return affectationFacade.historique(batiment);
    }

    @PostMapping
    public Map<String, Object> reaffecter(@RequestBody ReaffectationDto dto) {
        return affectationFacade.reaffecter(dto, SecurityUtils.exigerDroit(Profil.Droit.AFFECTATION_ECRIRE));
    }
}
