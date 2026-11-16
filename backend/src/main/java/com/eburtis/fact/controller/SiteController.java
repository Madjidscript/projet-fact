package com.eburtis.fact.controller;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.eburtis.fact.facade.SiteFacade;
import com.eburtis.fact.presentation.dto.AbonnementDto;
import com.eburtis.fact.presentation.dto.DemandeSiteDto;
import com.eburtis.fact.repository.SiteRepository;
import com.fasterxml.jackson.databind.JsonNode;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** L'API du site public : aucune session exigée (/api/public/** est ouvert dans SecurityConfig). */
@RestController
@RequestMapping("/api/public")
public class SiteController {

    private final SiteFacade siteFacade;

    public SiteController(SiteFacade siteFacade) {
        this.siteFacade = siteFacade;
    }

    /** Recherche : les critères du § 3.2 ; ouest, sud, est, nord limitent au cadre de la carte. */
    @GetMapping("/biens")
    public JsonNode rechercher(@RequestParam(required = false) String operation, @RequestParam(required = false) List<String> types,
                               @RequestParam(required = false) Integer commune, @RequestParam(required = false) Integer quartier,
                               @RequestParam(required = false) Long prixMin, @RequestParam(required = false) Long prixMax,
                               @RequestParam(required = false) Integer pieces, @RequestParam(required = false) Integer surface,
                               @RequestParam(required = false) List<String> options, @RequestParam(required = false) String tri,
                               @RequestParam(required = false) Double ouest, @RequestParam(required = false) Double sud,
                               @RequestParam(required = false) Double est, @RequestParam(required = false) Double nord) {
        return siteFacade.rechercher(new SiteRepository.Criteres(operation, types, commune, quartier, prixMin, prixMax, pieces, surface,
                options, tri, ouest, sud, est, nord));
    }

    @GetMapping("/biens/une")
    public JsonNode aLaUne() {
        return siteFacade.aLaUne();
    }

    @GetMapping("/biens/{slug}")
    public Map<String, Object> fiche(@PathVariable String slug) {
        return siteFacade.fiche(slug);
    }

    @GetMapping("/photos/{id}")
    public ResponseEntity<byte[]> photo(@PathVariable int id) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(7)).cachePublic()).body(siteFacade.photo(id));
    }

    @GetMapping("/chiffres")
    public Map<String, Object> chiffres() {
        return siteFacade.chiffres();
    }

    @PostMapping(value = "/demandes", consumes = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> demander(@RequestBody DemandeSiteDto dto, HttpServletRequest requete) {
        return siteFacade.demander(dto, List.of(), poste(requete));
    }

    /** La même, avec des pièces jointes : la demande en partie « demande » (JSON), les fichiers en « fichiers ». */
    @PostMapping(value = "/demandes", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> demanderAvecPieces(@RequestPart("demande") DemandeSiteDto dto,
                                                  @RequestPart(value = "fichiers", required = false) List<MultipartFile> fichiers,
                                                  HttpServletRequest requete) {
        return siteFacade.demander(dto, fichiers, poste(requete));
    }

    @PostMapping("/alertes")
    public Map<String, Object> abonner(@RequestBody AbonnementDto dto, HttpServletRequest requete) {
        return siteFacade.abonner(dto, poste(requete));
    }

    @PostMapping("/alertes/arreter")
    public Map<String, Object> desabonner(@RequestParam String jeton) {
        return siteFacade.desabonner(jeton);
    }

    private static String poste(HttpServletRequest r) {
        String relais = r.getHeader("X-Forwarded-For");
        return relais != null && !relais.isBlank() ? relais.split(",")[0].trim() : r.getRemoteAddr();
    }
}
