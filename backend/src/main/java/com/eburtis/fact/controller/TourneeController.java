package com.eburtis.fact.controller;

import java.time.LocalDate;
import java.util.Map;

import com.eburtis.fact.facade.TourneeFacade;
import com.eburtis.fact.presentation.dto.TourneeSaisieDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Les tournées des agents (§ 4.6) : préparation, tournée du jour, pointage, compte rendu. */
@RestController
@RequestMapping("/api/tournees")
public class TourneeController {

    private final TourneeFacade tourneeFacade;

    public TourneeController(TourneeFacade tourneeFacade) {
        this.tourneeFacade = tourneeFacade;
    }

    /** Les tournées d'un jour (aujourd'hui par défaut) ; l'agent ne voit que la sienne. */
    @GetMapping
    public JsonNode tournees(@RequestParam(required = false) LocalDate jour, @RequestParam(required = false) Integer agent) {
        return tourneeFacade.duJour(jour, agent, SecurityUtils.utilisateurConnecte());
    }

    /** La tournée d'aujourd'hui du compte connecté (corps vide s'il n'en a pas). */
    @GetMapping("/la-mienne")
    public Map<String, Object> laMienne() {
        return tourneeFacade.maTournee(SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/agents")
    public JsonNode agents() {
        return tourneeFacade.agents(SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/suggestions")
    public JsonNode suggestions(@RequestParam(required = false) LocalDate jour) {
        return tourneeFacade.suggestions(jour, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/{id}")
    public Map<String, Object> tournee(@PathVariable int id) {
        return tourneeFacade.tournee(id, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping
    public Map<String, Object> enregistrer(@RequestBody TourneeSaisieDto dto) {
        return tourneeFacade.enregistrer(dto, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/{id}/etapes/{idEtape}/pointage")
    public Map<String, Object> pointer(@PathVariable int id, @PathVariable int idEtape, @RequestBody TourneeSaisieDto.PointageDto dto) {
        return tourneeFacade.pointer(id, idEtape, dto, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/{id}/terminer")
    public Map<String, Object> terminer(@PathVariable int id, @RequestBody Map<String, String> corps) {
        return tourneeFacade.terminer(id, corps.get("compteRendu"), SecurityUtils.utilisateurConnecte());
    }
}
