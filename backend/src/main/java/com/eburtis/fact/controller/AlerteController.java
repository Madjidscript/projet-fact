package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.facade.AlerteFacade;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Les alertes du compte connecté : chacun ne voit que les siennes. */
@RestController
@RequestMapping("/api/alertes")
public class AlerteController {

    private final AlerteFacade alerteFacade;

    public AlerteController(AlerteFacade alerteFacade) {
        this.alerteFacade = alerteFacade;
    }

    @GetMapping
    public JsonNode lister() {
        return alerteFacade.lister(SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/compte")
    public JsonNode compte() {
        return alerteFacade.compte(SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/{id}/lue")
    public Map<String, Object> lue(@PathVariable long id) {
        return alerteFacade.marquerLue(id, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/lues")
    public Map<String, Object> toutesLues() {
        return alerteFacade.toutMarquerLu(SecurityUtils.utilisateurConnecte());
    }
}
