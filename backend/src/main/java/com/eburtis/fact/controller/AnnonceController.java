package com.eburtis.fact.controller;

import java.util.List;
import java.util.Map;

import com.eburtis.fact.facade.AnnonceFacade;
import com.eburtis.fact.presentation.dto.AnnonceSaisieDto;
import com.eburtis.fact.presentation.dto.MotifDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Les annonces côté application (§ 4.10). Lecture et rédaction : droit annonce.ecrire (gestionnaire)
 * ou annonce.valider (éditeur web, Direction) ; validation : annonce.valider. Le site public lit
 * les annonces en ligne par /api/public.
 */
@RestController
public class AnnonceController {

    private final AnnonceFacade annonceFacade;

    public AnnonceController(AnnonceFacade annonceFacade) {
        this.annonceFacade = annonceFacade;
    }

    @GetMapping("/api/annonces")
    public JsonNode lister(@RequestParam(required = false) String statut) {
        return annonceFacade.lister(SecurityUtils.utilisateurConnecte(), statut == null || statut.isBlank() ? null : statut);
    }

    @GetMapping("/api/annonces/{id}")
    public Map<String, Object> fiche(@PathVariable int id) {
        return annonceFacade.fiche(id, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/api/lots/{idLot}/annonce")
    public Map<String, Object> duLot(@PathVariable int idLot) {
        return annonceFacade.duLot(idLot, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces")
    public Map<String, Object> enregistrer(@RequestBody AnnonceSaisieDto dto) {
        return annonceFacade.enregistrer(dto, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/{id}/soumettre")
    public Map<String, Object> soumettre(@PathVariable int id) {
        return annonceFacade.soumettre(id, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/{id}/valider")
    public Map<String, Object> valider(@PathVariable int id) {
        return annonceFacade.valider(id, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/{id}/refuser")
    public Map<String, Object> refuser(@PathVariable int id, @RequestBody MotifDto dto) {
        return annonceFacade.refuser(id, dto.motif(), SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/{id}/retirer")
    public Map<String, Object> retirer(@PathVariable int id, @RequestBody(required = false) MotifDto dto) {
        return annonceFacade.retirer(id, dto == null ? null : dto.motif(), SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/{id}/une")
    public Map<String, Object> aLaUne(@PathVariable int id, @RequestParam boolean valeur) {
        return annonceFacade.aLaUne(id, valeur, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping(value = "/api/annonces/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> ajouterPhotos(@PathVariable int id, @RequestParam("fichiers") List<MultipartFile> fichiers) {
        return annonceFacade.ajouterPhotos(id, fichiers, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/{id}/photos/ordre")
    public Map<String, Object> ordonner(@PathVariable int id, @RequestBody List<Integer> ordre) {
        return annonceFacade.ordonnerPhotos(id, ordre, SecurityUtils.utilisateurConnecte());
    }

    @PostMapping("/api/annonces/photos/{idPhoto}/supprimer")
    public Map<String, Object> supprimerPhoto(@PathVariable int idPhoto) {
        return annonceFacade.supprimerPhoto(idPhoto, SecurityUtils.utilisateurConnecte());
    }

    @GetMapping("/api/annonces/photos/{idPhoto}")
    public ResponseEntity<byte[]> photo(@PathVariable int idPhoto) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).cacheControl(CacheControl.noCache())
                .body(annonceFacade.photo(idPhoto, SecurityUtils.utilisateurConnecte()));
    }
}
