package com.eburtis.fact.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Un recensement fait sur le terrain (§ 4.6) : le bâtiment tel que l'agent l'a relevé, sa position
 * GPS et sa précision, et la liste de ses lots. {@code cle} est tirée sur le téléphone : un envoi
 * rejoué après une coupure de réseau ne crée pas un second bâtiment.
 */
public record RecensementDto(UUID cle, OffsetDateTime saisiLe, String nom, String type, String adresse, Integer idCommune,
                             Integer idQuartier, Double latitude, Double longitude, Double precisionGps, Integer niveaux,
                             Integer idProprietaire, String description, List<LotTerrain> lots) {

    /** Un lot relevé : porte, étage, type, statut constaté, pièces et surface si connues. */
    public record LotTerrain(String numero, Integer etage, String type, String statut, Integer pieces, Double surface, String description) {
    }
}
