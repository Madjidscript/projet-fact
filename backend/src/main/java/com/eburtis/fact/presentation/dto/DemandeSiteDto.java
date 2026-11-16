package com.eburtis.fact.presentation.dto;

import java.time.LocalDate;
import java.util.Map;

/**
 * Une demande envoyée depuis le site (§ 3.3 à 3.5) : visite d'un bien ({@code slug}), confier un
 * bien (MANDAT), devis, estimation ou contact. {@code details} porte les champs propres à chaque
 * formulaire ; {@code site} est un champ piège laissé vide par les personnes, rempli par les robots.
 */
public record DemandeSiteDto(String type, String slug, String nom, String telephone, String email, LocalDate dateSouhaitee,
                             String creneau, String message, Map<String, Object> details, Double latitude, Double longitude,
                             Boolean consentement, String site) {
}
