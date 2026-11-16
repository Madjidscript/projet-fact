package com.eburtis.fact.controller;

import java.util.Map;

import com.eburtis.fact.facade.EspaceFacade;
import com.eburtis.fact.security.SecurityUtils;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** L'espace propriétaire (§ 4.9). ?proprietaire=ID : la Direction ouvre l'espace d'un propriétaire. */
@RestController
@RequestMapping("/api/espace")
public class EspaceController {

    /** La réponse du propriétaire à un devis. */
    public record ReponseDto(Boolean accord, String motif) {
    }

    private final EspaceFacade espaceFacade;

    public EspaceController(EspaceFacade espaceFacade) {
        this.espaceFacade = espaceFacade;
    }

    @GetMapping
    public Map<String, Object> espace(@RequestParam(required = false) Integer proprietaire) {
        return espaceFacade.espace(SecurityUtils.utilisateurConnecte(), proprietaire);
    }

    @PostMapping("/devis/{id}")
    public Map<String, Object> repondre(@PathVariable int id, @RequestBody ReponseDto r) {
        return espaceFacade.repondre(id, Boolean.TRUE.equals(r.accord()), r.motif(), SecurityUtils.utilisateurConnecte());
    }
}
