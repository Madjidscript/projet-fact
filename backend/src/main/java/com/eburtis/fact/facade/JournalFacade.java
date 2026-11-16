package com.eburtis.fact.facade;

import java.util.LinkedHashMap;
import java.util.Map;

import com.eburtis.fact.repository.JournalRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Service;

/** Lecture du journal des actions (SIG-22), réservée à la Direction et à l'administrateur. */
@Service
public class JournalFacade {

    private final JournalRepository journalRepository;
    private final ObjectMapper json;

    public JournalFacade(JournalRepository journalRepository, ObjectMapper json) {
        this.journalRepository = journalRepository;
        this.json = json;
    }

    /** Les dernières actions, de 1 à 1 000 lignes, et les natures d'objets pour le filtre. */
    public Map<String, Object> dernieres(int limite, String objet, String cle) {
        Map<String, Object> r = new LinkedHashMap<>();
        try {
            r.put("lignes", json.readTree(journalRepository.dernieres(Math.max(1, Math.min(limite, 1000)), objet, cle)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Journal illisible", e);
        }
        r.put("objets", journalRepository.objets());
        return r;
    }
}
