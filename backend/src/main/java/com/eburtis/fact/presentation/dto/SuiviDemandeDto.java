package com.eburtis.fact.presentation.dto;

import java.time.OffsetDateTime;

/** Le suivi d'une demande : statut (§ 3.8), note interne, qui la traite, date du rendez-vous. */
public record SuiviDemandeDto(String statut, String note, Integer idCompte, OffsetDateTime rdvLe) {
}
