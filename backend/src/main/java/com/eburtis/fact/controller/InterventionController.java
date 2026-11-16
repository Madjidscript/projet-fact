package com.eburtis.fact.controller;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.InterventionFacade;
import com.eburtis.fact.presentation.dto.EtapeInterventionDto;
import com.eburtis.fact.presentation.dto.InterventionSaisieDto;
import com.eburtis.fact.presentation.dto.PrestataireSaisieDto;
import com.eburtis.fact.security.SecurityUtils;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Interventions et travaux (SIG-13) : lecture avec le droit travaux.lire, signalement et photos avec
 * travaux.ecrire ; chaque étape du cycle revérifie qui peut la faire (InterventionFacade).
 */
@RestController
@RequestMapping("/api")
public class InterventionController {

    private final InterventionFacade interventionFacade;

    public InterventionController(InterventionFacade interventionFacade) {
        this.interventionFacade = interventionFacade;
    }

    /** {@code filtre} : « ouvertes », « closes », ou absent ; {@code batiment} restreint à un bâtiment. */
    @GetMapping("/interventions")
    public JsonNode lister(@RequestParam(required = false) String filtre, @RequestParam(required = false) Integer batiment) {
        return interventionFacade.lister(SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE), filtre, batiment);
    }

    @GetMapping("/interventions/synthese")
    public Map<String, Object> synthese() {
        return interventionFacade.synthese(SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE));
    }

    @GetMapping("/interventions/{id}")
    public Map<String, Object> fiche(@PathVariable int id) {
        return interventionFacade.fiche(id, SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE));
    }

    @PostMapping("/interventions")
    public JsonNode signaler(@RequestBody InterventionSaisieDto dto) {
        return interventionFacade.signaler(dto, SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_ECRIRE));
    }

    /** {@code action} : affecter, demarrer, valider, terminer, cloturer, annuler. */
    @PostMapping("/interventions/{id}/{action}")
    public Map<String, Object> etape(@PathVariable int id, @PathVariable String action, @RequestBody EtapeInterventionDto dto) {
        return interventionFacade.etape(id, action, dto, SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE));
    }

    @PostMapping("/interventions/{id}/photos")
    public JsonNode photos(@PathVariable int id, @RequestParam String moment, @RequestParam("fichiers") List<MultipartFile> fichiers)
            throws IOException {
        return interventionFacade.ajouterPhotos(id, moment, fichiers, SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_ECRIRE));
    }

    @GetMapping("/interventions/photos/{idPhoto}")
    public ResponseEntity<byte[]> photo(@PathVariable int idPhoto) {
        byte[] jpeg = interventionFacade.photo(idPhoto, SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE));
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePrivate()).body(jpeg);
    }

    @GetMapping("/prestataires")
    public JsonNode prestataires(@RequestParam(defaultValue = "false") boolean inactifs) {
        SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE);
        return interventionFacade.prestataires(inactifs);
    }

    @PostMapping("/prestataires")
    public JsonNode enregistrerPrestataire(@RequestBody PrestataireSaisieDto dto) {
        return interventionFacade.enregistrerPrestataire(dto, SecurityUtils.exigerDroit(Profil.Droit.TRAVAUX_LIRE));
    }
}
