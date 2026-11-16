package com.eburtis.fact.presentation.dto;

import java.util.List;

/** Création ({@code id} nul) ou modification d'un lot. Les montants sont en FCFA, sans décimale. */
public record LotSaisieDto(Integer id, Integer idBatiment, String numero, Integer etage, String type, Double surface,
                           Integer pieces, Integer chambres, Integer sallesEau, List<String> equipements,
                           Integer loyerReference, Integer charges, String statut, String description) {
}
