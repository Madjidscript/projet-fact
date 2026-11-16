package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.LocationFacade;
import com.eburtis.fact.presentation.dto.BailSaisieDto;
import com.eburtis.fact.presentation.dto.EncaissementSaisieDto;
import com.eburtis.fact.presentation.dto.FinBailDto;
import com.eburtis.fact.presentation.dto.OccupantSaisieDto;
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

/**
 * Gestion locative : occupants, baux, encaissements, quittances. Lecture : droit location.lire ;
 * occupants et baux : location.ecrire ; encaissements : encaissement.ecrire (matrice du § 2.2).
 */
@RestController
@RequestMapping("/api")
public class LocationController {

    private final LocationFacade locationFacade;

    public LocationController(LocationFacade locationFacade) {
        this.locationFacade = locationFacade;
    }

    // ------------------------------------------------------------------ lecture
    @GetMapping("/loyers/synthese")
    public Map<String, Object> synthese() {
        return locationFacade.synthese(lecteur());
    }

    /** {@code filtre} : « actifs », « retard », ou absent pour tous les baux. */
    @GetMapping("/baux")
    public JsonNode baux(@RequestParam(required = false) String filtre, @RequestParam(required = false) Integer batiment) {
        return locationFacade.baux(lecteur(), filtre, batiment);
    }

    @GetMapping("/baux/{id}")
    public Map<String, Object> bail(@PathVariable int id) {
        return locationFacade.bail(id, lecteur());
    }

    @GetMapping("/lots/{id}/location")
    public Map<String, Object> locationDuLot(@PathVariable int id) {
        return locationFacade.locationDuLot(id, lecteur());
    }

    @GetMapping("/occupants")
    public JsonNode occupants() {
        SecurityUtils.exigerDroit(Profil.Droit.LOCATION_ECRIRE);
        return locationFacade.occupants();
    }

    @GetMapping("/occupants/{id}")
    public Map<String, Object> occupant(@PathVariable int id) {
        return locationFacade.occupant(id, SecurityUtils.exigerDroit(Profil.Droit.LOCATION_ECRIRE));
    }

    /** Le reçu d'un paiement, ouvert par le locataire depuis le lien envoyé (sans compte). */
    @GetMapping("/public/recus/{jeton}")
    public ResponseEntity<byte[]> recu(@PathVariable java.util.UUID jeton) {
        Map.Entry<String, byte[]> q = locationFacade.recuPublic(jeton);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(q.getKey()).build().toString())
                .body(q.getValue());
    }

    @GetMapping("/encaissements/{id}/quittance")
    public ResponseEntity<byte[]> quittance(@PathVariable long id) {
        Map.Entry<String, byte[]> q = locationFacade.quittance(id, lecteur());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(q.getKey()).build().toString())
                .body(q.getValue());
    }

    // ------------------------------------------------------------------ écriture
    @PostMapping("/occupants")
    public JsonNode enregistrerOccupant(@RequestBody OccupantSaisieDto dto) {
        return locationFacade.enregistrerOccupant(dto, SecurityUtils.exigerDroit(Profil.Droit.LOCATION_ECRIRE));
    }

    @PostMapping("/baux")
    public Map<String, Object> creerBail(@RequestBody BailSaisieDto dto) {
        return locationFacade.creerBail(dto, SecurityUtils.exigerDroit(Profil.Droit.LOCATION_ECRIRE));
    }

    @PostMapping("/baux/{id}/preavis")
    public JsonNode preavis(@PathVariable int id, @RequestBody FinBailDto dto) {
        return locationFacade.preavis(id, dto, SecurityUtils.exigerDroit(Profil.Droit.LOCATION_ECRIRE));
    }

    @PostMapping("/baux/{id}/terminer")
    public JsonNode terminer(@PathVariable int id, @RequestBody FinBailDto dto) {
        return locationFacade.terminer(id, dto, SecurityUtils.exigerDroit(Profil.Droit.LOCATION_ECRIRE));
    }

    @PostMapping("/encaissements")
    public Map<String, Object> encaisser(@RequestBody EncaissementSaisieDto dto) {
        return locationFacade.encaisser(dto, SecurityUtils.exigerDroit(Profil.Droit.ENCAISSEMENT_ECRIRE));
    }

    @PostMapping("/encaissements/{id}/annuler")
    public JsonNode annuler(@PathVariable long id, @RequestBody Map<String, String> corps) {
        return locationFacade.annuler(id, corps.get("motif"), lecteur());
    }

    /**
     * Un lecteur de la gestion locative. Le propriétaire n'y a pas accès : il voit la situation de ses
     * lots dans son espace, sans les données personnelles de ses locataires (§ 4.9).
     */
    private static com.eburtis.fact.domain.Utilisateur lecteur() {
        var u = SecurityUtils.exigerDroit(Profil.Droit.LOCATION_LIRE);
        if (u.profil() == Profil.PROPRIETAIRE) {
            throw com.eburtis.fact.exception.ErreurMetier.refuse("Consultez la situation de vos lots dans votre espace propriétaire.");
        }
        return u;
    }
}
