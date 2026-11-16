package com.eburtis.fact.presentation.dto;

import java.util.List;

/** Création ou modification d'un compte. {@code droits} : ceux accordés en plus de ceux du profil. */
public record CompteSaisieDto(String identifiant, String nom, String fonction, String email, String telephone,
                              String profil, List<String> droits, Boolean actif) {
}
