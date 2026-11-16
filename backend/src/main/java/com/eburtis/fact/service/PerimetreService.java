package com.eburtis.fact.service;

import java.util.List;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;

import org.springframework.stereotype.Service;

/**
 * Jusqu'où va le regard d'un compte sur le patrimoine (matrice du § 2.2, mentions « ses ») :
 * le propriétaire ne voit que ses biens, le gestionnaire que son portefeuille, les autres
 * profils tout le patrimoine. Toute lecture et toute écriture de bâtiment ou de lot passe par
 * ce filtre : c'est lui qui garantit qu'« un propriétaire ne voit que ses biens ».
 */
@Service
public class PerimetreService {

    /**
     * La condition SQL à poser sur la vue pat.v_batiment (alias {@code v}) ; ses paramètres
     * sont ajoutés à {@code params}.
     */
    public String batiments(Utilisateur u, List<Object> params) {
        return switch (u.profil().portee()) {
            case TOUT -> "true";
            case SON_PORTEFEUILLE -> {
                params.add(u.id());
                params.add(u.id());
                yield "(v.id_gestionnaire = ? OR v.id_suppleant = ?)";
            }
            case SES_BIENS -> {
                params.add(u.id());
                yield "v.id_compte_proprietaire = ?";
            }
        };
    }

    /** Vrai si le compte voit tout le patrimoine. */
    public boolean voitTout(Utilisateur u) {
        return u.profil().portee() == Profil.Portee.TOUT;
    }

    /**
     * Vrai si le compte peut lire les coordonnées des propriétaires. « Les données personnelles
     * ne sont visibles que des profils qui en ont besoin » (§ 8) : ceux qui gèrent le patrimoine.
     */
    public boolean voitContacts(Utilisateur u) {
        return u.peut(Profil.Droit.PATRIMOINE_ECRIRE) || u.estDirection();
    }
}
