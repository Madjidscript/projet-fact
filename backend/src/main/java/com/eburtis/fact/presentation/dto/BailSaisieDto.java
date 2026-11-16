package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;

/**
 * Création d'un bail. L'occupant est soit choisi ({@code idOccupant}), soit créé dans la foulée
 * ({@code nouvelOccupant}). {@code avanceEncaissee} : l'avance versée à l'entrée est enregistrée
 * comme un encaissement, payé par {@code modeAvance}.
 */
public record BailSaisieDto(Integer idLot, Integer idOccupant, OccupantSaisieDto nouvelOccupant, LocalDate dateEffet,
                            Integer dureeMois, Integer loyer, Integer charges, Integer caution, Integer avanceMois,
                            Integer jourExigibilite, Boolean avanceEncaissee, String modeAvance, String notes) {
}
