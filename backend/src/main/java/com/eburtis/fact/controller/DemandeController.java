package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.facade.DemandeFacade;
import com.eburtis.fact.presentation.dto.SuiviDemandeDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Le suivi des demandes venues du site (§ 3.8) et la boîte d'envoi des messages aux visiteurs. */
@RestController
@RequestMapping("/api/demandes")
public class DemandeController {

    private final DemandeFacade demandeFacade;

    public DemandeController(DemandeFacade demandeFacade) {
        this.demandeFacade = demandeFacade;
    }

    @GetMapping
    public Map<String, Object> lister() {
        return demandeFacade.lister(SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/{id}")
    public Map<String, Object> fiche(@PathVariable int id) {
        return demandeFacade.fiche(id, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/{id}/suivi")
    public Map<String, Object> suivre(@PathVariable int id, @RequestBody SuiviDemandeDto dto) {
        return demandeFacade.suivre(id, dto, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/pieces/{id}")
    public ResponseEntity<byte[]> piece(@PathVariable int id) {
        Map.Entry<Object[], byte[]> p = demandeFacade.piece(id, SecurityUtils.utilisateurConnecte());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType((String) p.getKey()[2]))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename((String) p.getKey()[1]).build().toString())
                .body(p.getValue());
    }

    @GetMapping("/messages")
    public JsonNode messages() {
        return demandeFacade.messages(SecurityUtils.utilisateurConnecte());
    }

    /** Un message de la boîte d'envoi est parti (envoye=true) ou n'a pas lieu d'être envoyé (false). */
    @PostMapping("/messages/{id}")
    public Map<String, Object> marquer(@PathVariable int id, @RequestParam boolean envoye) {
        return demandeFacade.marquerMessage(id, envoye, SecurityUtils.utilisateurConnecte());
    }
}
