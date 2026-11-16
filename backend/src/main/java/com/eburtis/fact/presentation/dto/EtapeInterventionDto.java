package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;

/**
 * Le passage d'une intervention à l'étape suivante. Chaque étape lit les champs qui la concernent :
 * affecter (prestataire, date prévue), démarrer (constat, devis), valider (accord du propriétaire,
 * note), terminer (coût réel, note), clôturer (imputation, note), annuler (note : le motif).
 */
public record EtapeInterventionDto(Integer idPrestataire, LocalDate datePrevue, String constat, Integer devisMontant,
                                   Boolean accordProprietaire, Integer coutReel, String imputation, String note) {
}
