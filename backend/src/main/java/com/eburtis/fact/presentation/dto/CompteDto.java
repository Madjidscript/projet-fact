package com.eburtis.fact.presentation.dto;

import java.time.OffsetDateTime;
import java.util.List;

/** Un compte tel que le voit l'administration des comptes (sans son mot de passe). */
public record CompteDto(int id, String identifiant, String nom, String fonction, String email, String telephone,
                        String profil, String libelleProfil, List<String> droits, boolean actif, boolean doitChanger,
                        OffsetDateTime derniereConnexion, OffsetDateTime creeLe) {
}
