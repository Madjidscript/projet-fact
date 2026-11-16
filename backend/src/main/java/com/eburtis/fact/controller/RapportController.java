package com.eburtis.fact.controller;

import java.time.LocalDate;
import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.RapportFacade;
import com.eburtis.fact.facade.ReleveFacade;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Tableaux de bord et rapports (§ 4.8) : indicateurs d'une période de mois (« du » et « au » sous la
 * forme AAAA-MM, douze derniers mois par défaut), leurs exports, et le relevé d'un propriétaire.
 */
@RestController
public class RapportController {

    private static final MediaType EXCEL = MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final RapportFacade rapportFacade;
    private final ReleveFacade releveFacade;

    public RapportController(RapportFacade rapportFacade, ReleveFacade releveFacade) {
        this.rapportFacade = rapportFacade;
        this.releveFacade = releveFacade;
    }

    @GetMapping("/api/rapports/indicateurs")
    public Map<String, Object> indicateurs(@RequestParam(required = false) String du, @RequestParam(required = false) String au) {
        return rapportFacade.indicateurs(sansProprietaire(), RapportFacade.periode(du, au));
    }

    @GetMapping("/api/rapports/export/excel")
    public ResponseEntity<byte[]> excel(@RequestParam(required = false) String du, @RequestParam(required = false) String au) {
        var u = sansProprietaire();
        return fichier(rapportFacade.excel(u, RapportFacade.periode(du, au)), EXCEL, "tableau-de-bord-" + LocalDate.now() + ".xlsx", false);
    }

    @GetMapping("/api/rapports/export/pdf")
    public ResponseEntity<byte[]> pdf(@RequestParam(required = false) String du, @RequestParam(required = false) String au) {
        var u = sansProprietaire();
        return fichier(rapportFacade.pdf(u, RapportFacade.periode(du, au)), MediaType.APPLICATION_PDF,
                "tableau-de-bord-" + LocalDate.now() + ".pdf", true);
    }

    @GetMapping("/api/proprietaires/{id}/releve")
    public Map<String, Object> releve(@PathVariable int id, @RequestParam(required = false) String du,
                                      @RequestParam(required = false) String au) {
        return releveFacade.donnees(id, RapportFacade.periode(du, au), SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_LIRE));
    }

    @GetMapping("/api/proprietaires/{id}/releve/pdf")
    public ResponseEntity<byte[]> relevePdf(@PathVariable int id, @RequestParam(required = false) String du,
                                            @RequestParam(required = false) String au) {
        Map.Entry<String, byte[]> r = releveFacade.pdf(id, RapportFacade.periode(du, au), SecurityUtils.exigerDroit(Profil.Droit.PATRIMOINE_LIRE));
        return fichier(r.getValue(), MediaType.APPLICATION_PDF, r.getKey(), true);
    }

    /** Les indicateurs nomment des locataires (impayés) : le propriétaire a son espace, pas ces rapports. */
    private static com.eburtis.fact.domain.Utilisateur sansProprietaire() {
        var u = SecurityUtils.exigerDroit(Profil.Droit.TABLEAU_BORD);
        if (u.profil() == Profil.PROPRIETAIRE) {
            throw com.eburtis.fact.exception.ErreurMetier.refuse("Retrouvez vos chiffres dans votre espace propriétaire.");
        }
        return u;
    }

    private static ResponseEntity<byte[]> fichier(byte[] corps, MediaType type, String nom, boolean enLigne) {
        ContentDisposition d = (enLigne ? ContentDisposition.inline() : ContentDisposition.attachment()).filename(nom).build();
        return ResponseEntity.ok().contentType(type).header(HttpHeaders.CONTENT_DISPOSITION, d.toString()).body(corps);
    }
}
