package com.eburtis.fact.facade;

import java.util.Map;

import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.repository.AlerteRepository;
import com.fasterxml.jackson.databind.JsonNode;

import org.springframework.stereotype.Component;

/** Les alertes d'un compte : chacun ne lit et ne marque que les siennes. */
@Component
public class AlerteFacade {

    private final AlerteRepository alerteRepository;

    public AlerteFacade(AlerteRepository alerteRepository) {
        this.alerteRepository = alerteRepository;
    }

    public JsonNode lister(Utilisateur u) {
        return alerteRepository.lister(u.id(), 100);
    }

    public JsonNode compte(Utilisateur u) {
        return alerteRepository.compte(u.id());
    }

    public Map<String, Object> marquerLue(long id, Utilisateur u) {
        return Map.of("modifiees", alerteRepository.marquerLue(id, u.id()));
    }

    public Map<String, Object> toutMarquerLu(Utilisateur u) {
        return Map.of("modifiees", alerteRepository.toutMarquerLu(u.id()));
    }
}
