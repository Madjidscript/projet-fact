package com.eburtis.fact.domain;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Une commune du référentiel (table adm.commune). {@code latitude} et {@code longitude}
 * sont celles de son point de centrage, en attendant ses limites officielles.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Commune(int id, String code, String libelle, String district, double latitude, double longitude,
                      boolean limitesConnues, List<Quartier> quartiers) {

    /** La même commune, sans ses quartiers : pour les listes. */
    public Commune sansQuartiers() {
        return new Commune(id, code, libelle, district, latitude, longitude, limitesConnues, null);
    }
}
