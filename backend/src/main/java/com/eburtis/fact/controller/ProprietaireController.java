package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Les propriétaires mandants. Un compte Propriétaire n'y voit que sa propre fiche. */
@RestController
@RequestMapping("/api/proprietaires")
public class ProprietaireController {

    private final ProprietaireFacade proprietaireFacade;
    private final com.eburtis.fact.facade.CompteFacade compteFacade;

    public ProprietaireController(ProprietaireFacade proprietaireFacade, com.eburtis.fact.facade.CompteFacade compteFacade) {
        this.proprietaireFacade = proprietaireFacade;
        this.compteFacade = compteFacade;
    }

    /** Ouvre l'espace propriétaire : crée son compte à son e-mail ; rend le mot de passe provisoire. */
    @PostMapping("/{id}/espace")
    public Map<String, Object> ouvrirEspace(@PathVariable int id) {
        return proprietaireFacade.ouvrirEspace(id, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE), compteFacade);
    }

    @GetMapping
    public JsonNode lister(@RequestParam(defaultValue = "false") boolean archives) {
        return proprietaireFacade.lister(SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_LIRE), archives);
    }

    @GetMapping("/{id}")
    public Map<String, Object> fiche(@PathVariable int id) {
        return proprietaireFacade.fiche(id, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_LIRE));
    }

    /** Les comptes de l'espace propriétaire à rattacher : pour la Direction. */
    @GetMapping("/comptes")
    public JsonNode comptes() {
        if (!SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE).estDirection()) {
            throw ErreurMetier.refuse("Réservé à la Direction.");
        }
        return proprietaireFacade.comptes();
    }

    @PostMapping
    public JsonNode enregistrer(@RequestBody ProprietaireSaisieDto dto) {
        return proprietaireFacade.enregistrer(dto, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @PostMapping("/{id}/archiver")
    public JsonNode archiver(@PathVariable int id, @RequestParam(defaultValue = "true") boolean archive) {
        var u = SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE);
        if (!u.estDirection()) {
            throw ErreurMetier.refuse("Seule la Direction archive ou restaure un propriétaire.");
        }
        return proprietaireFacade.archiver(id, archive, u);
    }
}
