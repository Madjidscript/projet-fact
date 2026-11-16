package com.eburtis.fact.presentation.dto;

/** Création ({@code id} nul) ou modification d'un occupant. */
public record OccupantSaisieDto(Integer id, String typePersonne, String nom, String prenoms, String telephone, String telephone2,
                                String email, String pieceType, String pieceNumero, String profession, String employeur,
                                String garantNom, String garantTelephone, String notes) {
}
