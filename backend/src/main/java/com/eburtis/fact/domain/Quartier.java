package com.eburtis.fact.domain;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Un quartier d'une commune (table adm.quartier). */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Quartier(int id, int idCommune, String code, String libelle) {
}
