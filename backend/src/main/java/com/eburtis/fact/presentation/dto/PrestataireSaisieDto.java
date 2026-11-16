package com.eburtis.fact.presentation.dto;

/** Un technicien ou une entreprise de travaux. */
public record PrestataireSaisieDto(Integer id, String nom, String specialite, String telephone, String email, String notes,
                                   Boolean actif) {
}
