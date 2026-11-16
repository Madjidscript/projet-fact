package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.CompteFacade;
import com.eburtis.fact.presentation.dto.CompteSaisieDto;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administration des comptes : lecture pour la Direction et l'administrateur, écriture pour
 * l'administrateur seul (§ 2.2). Pas de suppression : un compte se désactive (§ 8).
 */
@RestController
@RequestMapping("/api/comptes")
public class CompteController {

    private final CompteFacade compteFacade;

    public CompteController(CompteFacade compteFacade) {
        this.compteFacade = compteFacade;
    }

    @GetMapping
    public Map<String, Object> lister() {
        SecurityUtils.exigerDroit(Profil.Droit.PARAMETRES_LIRE);
        return compteFacade.lister();
    }

    @PostMapping
    public Map<String, Object> enregistrer(@RequestBody CompteSaisieDto dto) {
        return compteFacade.enregistrer(dto, SecurityUtils.exigerDroit(Profil.Droit.COMPTES));
    }

    @PostMapping("/{identifiant}/reinitialiser")
    public Map<String, Object> reinitialiser(@PathVariable String identifiant) {
        String mdp = compteFacade.reinitialiser(identifiant, SecurityUtils.exigerDroit(Profil.Droit.COMPTES));
        return Map.of("motDePasseProvisoire", mdp);
    }
}
