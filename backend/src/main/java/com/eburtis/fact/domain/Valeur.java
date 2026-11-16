package com.eburtis.fact.domain;

/** Un choix d'une liste de référence (table ref.valeur) : « TYPE_LOT / F3 / Appartement F3 ». */
public record Valeur(String domaine, String code, String libelle, int ordre) {
}
