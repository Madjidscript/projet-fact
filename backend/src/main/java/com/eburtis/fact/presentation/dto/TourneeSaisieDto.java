package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** La tournée d'un agent pour un jour : les bâtiments à visiter, chacun avec son motif. */
public record TourneeSaisieDto(Integer idAgent, LocalDate jour, String consigne, List<EtapeSaisie> etapes) {

    public record EtapeSaisie(Integer idBatiment, String motif, String consigne) {
    }

    /** Le pointage d'une étape sur place, peut-être envoyé plus tard depuis le téléphone. */
    public record PointageDto(OffsetDateTime pointeeLe, Double latitude, Double longitude, Double precision, String resultat,
                              String compteRendu) {
    }
}
