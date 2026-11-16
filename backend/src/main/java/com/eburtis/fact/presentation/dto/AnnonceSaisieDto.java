package com.eburtis.fact.presentation.dto;

/**
 * Création ou modification d'une annonce : le lot proposé, l'opération (LOCATION ou VENTE), le
 * texte, le prix et les conditions d'entrée, et le mode d'affichage de la position (§ 4.10).
 */
public record AnnonceSaisieDto(Integer id, Integer idLot, String operation, String titre, String description, Integer prix,
                               Integer charges, Integer cautionMois, Integer avanceMois, Boolean positionExacte) {
}
