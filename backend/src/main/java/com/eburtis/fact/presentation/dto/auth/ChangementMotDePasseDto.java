package com.eburtis.fact.presentation.dto.auth;

/** Changement du mot de passe de la personne connectée. */
public record ChangementMotDePasseDto(String actuel, String nouveau) {
}
