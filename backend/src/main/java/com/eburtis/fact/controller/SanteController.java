package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.facade.SanteFacade;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** État de la plateforme : version, base joignable et migrations appliquées. Adresse publique. */
@RestController
@RequestMapping("/api/sante")
public class SanteController {

    private final SanteFacade santeFacade;

    public SanteController(SanteFacade santeFacade) {
        this.santeFacade = santeFacade;
    }

    @GetMapping
    public Map<String, Object> sante() {
        return santeFacade.sante();
    }
}
