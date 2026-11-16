package com.eburtis.fact.controller;

import java.util.List;
import java.util.Map;

import com.eburtis.fact.facade.EtatDesLieuxFacade;
import com.eburtis.fact.presentation.dto.EtatDesLieuxDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Les états des lieux (§ 4.6) : envoi depuis le téléphone, liste d'un bail, PDF, photos. */
@RestController
public class EtatDesLieuxController {

    private final EtatDesLieuxFacade facade;

    public EtatDesLieuxController(EtatDesLieuxFacade facade) {
        this.facade = facade;
    }

    /** Parties : « etatDesLieux » (JSON), « signatureOccupant » et « signatureAgent » (PNG), « photos ». */
    @PostMapping(value = "/api/etats-des-lieux", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> recevoir(@RequestPart("etatDesLieux") EtatDesLieuxDto dto,
                                        @RequestPart("signatureOccupant") MultipartFile signatureOccupant,
                                        @RequestPart("signatureAgent") MultipartFile signatureAgent,
                                        @RequestPart(value = "photos", required = false) List<MultipartFile> photos) {
        return facade.recevoir(dto, signatureOccupant, signatureAgent, photos, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/api/baux/{id}/etats-des-lieux")
    public JsonNode duBail(@PathVariable int id) {
        return facade.duBail(id, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/api/etats-des-lieux/{id}")
    public JsonNode etatDesLieux(@PathVariable int id) {
        return facade.etatDesLieux(id, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/api/etats-des-lieux/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable int id) {
        Map.Entry<String, byte[]> p = facade.pdf(id, SecurityUtils.utilisateurConnecte());
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(p.getKey()).build().toString())
                .body(p.getValue());
    }

    @GetMapping("/api/etats-des-lieux/photos/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable int id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noCache())
                .body(facade.photo(id, SecurityUtils.utilisateurConnecte()));
    }
}
