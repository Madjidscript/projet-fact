package com.eburtis.fact.controller;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.MandatFacade;
import com.eburtis.fact.presentation.dto.MandatSaisieDto;
import com.eburtis.fact.presentation.dto.ResiliationDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Les mandats ; ils se lisent dans la fiche du propriétaire (GET /api/proprietaires/{id}). */
@RestController
@RequestMapping("/api/mandats")
public class MandatController {

    private final MandatFacade mandatFacade;

    public MandatController(MandatFacade mandatFacade) {
        this.mandatFacade = mandatFacade;
    }

    @PostMapping
    public JsonNode enregistrer(@RequestBody MandatSaisieDto dto) {
        return mandatFacade.enregistrer(dto, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @PostMapping(value = "/{id}/document", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonNode deposer(@PathVariable int id, @org.springframework.web.bind.annotation.RequestParam("fichier") org.springframework.web.multipart.MultipartFile f) {
        return mandatFacade.deposerDocument(id, f, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }

    @org.springframework.web.bind.annotation.GetMapping("/{id}/document")
    public org.springframework.http.ResponseEntity<byte[]> document(@PathVariable int id) {
        java.util.Map.Entry<String, byte[]> d = mandatFacade.document(id, SecurityUtils.utilisateurConnecte());
        return org.springframework.http.ResponseEntity.ok().contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.inline().filename(d.getKey()).build().toString())
                .body(d.getValue());
    }

    @PostMapping("/{id}/resilier")
    public JsonNode resilier(@PathVariable int id, @RequestBody ResiliationDto dto) {
        return mandatFacade.resilier(id, dto, SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_ECRIRE));
    }
}
