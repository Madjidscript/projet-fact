package com.eburtis.fact.presentation.dto;

/** Le signalement d'une panne ou d'un besoin de travaux, sur un bâtiment ou l'un de ses lots. */
public record InterventionSaisieDto(Integer idBatiment, Integer idLot, String categorie, String urgence, String titre,
                                    String description) {
}
