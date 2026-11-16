package com.eburtis.fact.presentation.dto;

/** Création ({@code id} nul) ou modification d'un bâtiment. La position est en WGS 84 (GPS). */
public record BatimentSaisieDto(Integer id, String nom, String type, String adresse, Integer idCommune, Integer idQuartier,
                                Double latitude, Double longitude, Double precisionGps, Integer niveaux,
                                Integer anneeConstruction, String description, Integer idProprietaire,
                                Integer idGestionnaire, Integer idSuppleant) {
}
