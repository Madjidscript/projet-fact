package com.eburtis.fact.controller;

import java.util.List;
import java.util.Map;

import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.TerrainFacade;
import com.eburtis.fact.presentation.dto.RecensementDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** L'application de terrain (§ 4.6) : préparation hors connexion, recensements, photos des bâtiments. */
@RestController
public class TerrainController {

    private final TerrainFacade terrainFacade;
    private final BatimentFacade batimentFacade;

    public TerrainController(TerrainFacade terrainFacade, BatimentFacade batimentFacade) {
        this.terrainFacade = terrainFacade;
        this.batimentFacade = batimentFacade;
    }

    @GetMapping("/api/terrain/preparation")
    public Map<String, Object> preparation() {
        return terrainFacade.preparation(SecurityUtils.utilisateurConnecte());
    }

    /** Un recensement : la partie « recensement » (JSON) et les photos en parties « photos ». */
    @PostMapping(value = "/api/terrain/recensements", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> recevoir(@RequestPart("recensement") RecensementDto dto,
                                        @RequestPart(value = "photos", required = false) List<MultipartFile> photos) {
        return terrainFacade.recevoir(dto, photos, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping(value = "/api/batiments/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public JsonNode ajouterPhotos(@PathVariable int id, @RequestParam("fichiers") List<MultipartFile> fichiers) {
        return batimentFacade.ajouterPhotos(id, fichiers, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/api/batiments/photos/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable int id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noCache())
                .body(batimentFacade.photo(id, SecurityUtils.utilisateurConnecte()));
    }
}
