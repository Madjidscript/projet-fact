package com.eburtis.fact.utils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.springframework.stereotype.Component;

/** Lecture du JSON que la base compose elle-même (json_agg, row_to_json). */
@Component
public class JsonUtils {

    private final ObjectMapper mapper;

    public JsonUtils(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public JsonNode lire(String texte) {
        try {
            return mapper.readTree(texte == null ? "null" : texte);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("JSON illisible rendu par la base", e);
        }
    }
}
