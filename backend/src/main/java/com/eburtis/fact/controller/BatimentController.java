package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.LotSaisieDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Bâtiments et lots (SIG-02, SIG-03). La lecture est limitée au périmètre du compte : ses biens
 * pour un propriétaire, son portefeuille pour un gestionnaire (§ 2.2).
 */
@RestController
@RequestMapping("/api")
public class BatimentController {

    private final BatimentFacade batimentFacade;

    public BatimentController(BatimentFacade batimentFacade) {
        this.batimentFacade = batimentFacade;
    }

    @GetMapping("/batiments")
    public JsonNode lister(@RequestParam(defaultValue = "false") boolean archives) {
        return batimentFacade.lister(SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_LIRE), archives);
    }

    @GetMapping("/batiments/{id}")
    public Map<String, Object> fiche(@PathVariable int id) {
        return batimentFacade.fiche(id, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_LIRE));
    }

    @GetMapping("/batiments/{id}/historique")
    public JsonNode historique(@PathVariable int id) {
        return batimentFacade.historique(id, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    /** Les gestionnaires à qui confier un bâtiment. */
    @GetMapping("/gestionnaires")
    public JsonNode gestionnaires() {
        SecurityUtils.exigerDroit(Profil.Droit.AFFECTATION_LIRE);
        return batimentFacade.gestionnaires();
    }

    @PostMapping("/batiments")
    public JsonNode enregistrer(@RequestBody BatimentSaisieDto dto) {
        return batimentFacade.enregistrer(dto, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @PostMapping("/batiments/{id}/valider")
    public JsonNode valider(@PathVariable int id) {
        return batimentFacade.valider(id, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @PostMapping("/batiments/{id}/archiver")
    public JsonNode archiver(@PathVariable int id, @RequestParam(defaultValue = "true") boolean archive) {
        return batimentFacade.archiver(id, archive, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @PostMapping("/lots")
    public JsonNode enregistrerLot(@RequestBody LotSaisieDto dto) {
        return batimentFacade.enregistrerLot(dto, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @PostMapping("/lots/{id}/archiver")
    public JsonNode archiverLot(@PathVariable int id, @RequestParam(defaultValue = "true") boolean archive) {
        return batimentFacade.archiverLot(id, archive, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }
}
