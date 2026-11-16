package com.eburtis.fact.domain;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * La personne connectée, telle que l'API la connaît pendant une requête (compte de la
 * table app.compte). Ses droits sont ceux de son profil, augmentés de ceux qui lui ont
 * été accordés un par un.
 */
public record Utilisateur(int id, String identifiant, String nom, String fonction, String email, Profil profil,
                          Set<String> droitsAccordes, boolean doitChanger, int versionSession) {

    /** Les droits du profil et ceux accordés en plus. */
    public Set<String> droits() {
        Set<String> tous = new LinkedHashSet<>(profil.droits());
        tous.addAll(droitsAccordes);
        return tous;
    }

    public boolean peut(String droit) {
        return profil.droits().contains(droit) || droitsAccordes.contains(droit);
    }

    public boolean estDirection() {
        return profil == Profil.DIRECTION || profil == Profil.ADMIN;
    }

    /** Ce qui est écrit dans « créé par » / « modifié par » et dans le journal. */
    public String signature() {
        String s = nom + " (" + identifiant + ")";
        return s.length() > 80 ? s.substring(0, 80) : s;
    }
}
