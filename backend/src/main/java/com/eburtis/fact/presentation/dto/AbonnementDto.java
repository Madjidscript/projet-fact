package com.eburtis.fact.presentation.dto;

import java.util.Map;

/** Un abonnement aux nouveaux biens d'une recherche (§ 3.7) : e-mail ou numéro WhatsApp, et les critères. */
public record AbonnementDto(String contact, Map<String, Object> criteres, Boolean consentement, String site) {
}
