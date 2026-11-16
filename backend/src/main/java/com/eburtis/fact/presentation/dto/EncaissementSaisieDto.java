package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Un paiement de loyer : bail, date, montant en FCFA, mode et référence de la transaction.
 * Sur le terrain (§ 4.6) s'y ajoutent la clé tirée sur le téléphone (un envoi rejoué ne crée pas
 * deux paiements), la position du paiement et la demande d'envoyer le reçu au locataire.
 */
public record EncaissementSaisieDto(Integer idBail, LocalDate datePaiement, Integer montant, String mode,
                                    String referencePaiement, String notes, UUID cle, Double latitude, Double longitude,
                                    Boolean envoyerRecu) {

    public EncaissementSaisieDto(Integer idBail, LocalDate datePaiement, Integer montant, String mode, String referencePaiement, String notes) {
        this(idBail, datePaiement, montant, mode, referencePaiement, notes, null, null, null, null);
    }
}
