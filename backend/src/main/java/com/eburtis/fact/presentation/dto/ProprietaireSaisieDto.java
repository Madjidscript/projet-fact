package com.eburtis.fact.presentation.dto;

/** Création ({@code id} nul) ou modification d'un propriétaire. */
public record ProprietaireSaisieDto(Integer id, String typePersonne, String nom, String prenoms, String telephone,
                                    String telephone2, String email, String adresse, String pieceType, String pieceNumero,
                                    String reversementMode, String reversementCoordonnees, Integer idCompte, String notes) {
}
