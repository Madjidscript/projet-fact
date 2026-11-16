package com.eburtis.fact.controller;

import java.util.List;
import java.util.Map;

import com.eburtis.fact.domain.Commune;
import com.eburtis.fact.domain.Valeur;
import com.eburtis.fact.facade.ReferentielFacade;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Les listes de référence : communes, quartiers, types de biens, équipements, statuts.
 * Adresses publiques : elles remplissent le moteur de recherche du site comme les
 * formulaires de l'application.
 */
@RestController
@RequestMapping("/api/referentiel")
public class ReferentielController {

    private final ReferentielFacade referentielFacade;

    public ReferentielController(ReferentielFacade referentielFacade) {
        this.referentielFacade = referentielFacade;
    }

    /** Tout le référentiel en un appel : ce que l'interface charge au démarrage. */
    @GetMapping
    public Map<String, Object> referentiel() {
        return referentielFacade.referentiel();
    }

    @GetMapping("/communes")
    public List<Commune> communes() {
        return referentielFacade.communes();
    }

    @GetMapping("/communes/{idCommune}/quartiers")
    public List<?> quartiers(@PathVariable int idCommune) {
        return referentielFacade.quartiers(idCommune);
    }

    @GetMapping("/listes")
    public Map<String, List<Valeur>> listes() {
        return referentielFacade.listes();
    }
}
