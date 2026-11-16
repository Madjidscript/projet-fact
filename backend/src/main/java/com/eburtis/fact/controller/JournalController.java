package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.facade.JournalFacade;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Le journal des actions : qui a modifié quoi, quand (SIG-22). */
@RestController
@RequestMapping("/api/journal")
public class JournalController {

    private final JournalFacade journalFacade;

    public JournalController(JournalFacade journalFacade) {
        this.journalFacade = journalFacade;
    }

    @GetMapping
    public Map<String, Object> lire(@RequestParam(defaultValue = "200") int limite,
                                    @RequestParam(required = false) String objet,
                                    @RequestParam(required = false) String cle) {
        SecurityUtils.exigerDroit(Profil.Droit.PARAMETRES_LIRE);
        return journalFacade.dernieres(limite, objet, cle);
    }
}
