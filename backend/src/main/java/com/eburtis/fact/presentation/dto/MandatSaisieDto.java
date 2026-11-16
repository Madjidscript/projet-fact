package com.eburtis.fact.presentation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Création ou modification d'un mandat ; {@code batiments} n'est lu que si {@code tousLesBiens} est faux. */
public record MandatSaisieDto(Integer id, Integer idProprietaire, String type, LocalDate dateDebut, Integer dureeMois,
                              Boolean reconductionTacite, BigDecimal tauxHonoraires, Integer seuilTravaux,
                              Boolean tousLesBiens, List<Integer> batiments, String notes) {
}
