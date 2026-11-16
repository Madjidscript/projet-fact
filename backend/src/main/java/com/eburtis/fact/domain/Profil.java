package com.eburtis.fact.domain;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Les profils du § 2.1 des spécifications, et les droits que chacun porte d'office
 * (matrice du § 2.2). Un compte peut recevoir des droits supplémentaires un par un
 * (colonne {@code droits} de app.compte) ; son profil fixe le socle.
 */
public enum Profil {

    PROPRIETAIRE("Propriétaire", "Mandant : accès en lecture à ses seuls biens",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.LOCATION_LIRE, Droit.TRAVAUX_LIRE, Droit.TABLEAU_BORD),
            Portee.SES_BIENS),

    AGENT("Agent de terrain", "Collecte, visites, états des lieux, recouvrement",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.PATRIMOINE_ECRIRE, Droit.LOCATION_LIRE, Droit.ENCAISSEMENT_ECRIRE,
                    Droit.TRAVAUX_LIRE, Droit.TRAVAUX_ECRIRE, Droit.TERRAIN),
            Portee.TOUT),

    GESTIONNAIRE("Gestionnaire", "Responsable d'un portefeuille de bâtiments",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.PATRIMOINE_ECRIRE, Droit.LOCATION_LIRE, Droit.LOCATION_ECRIRE,
                    Droit.ENCAISSEMENT_ECRIRE, Droit.TRAVAUX_LIRE, Droit.TRAVAUX_ECRIRE, Droit.ANNONCE_ECRIRE,
                    Droit.AFFECTATION_LIRE, Droit.TABLEAU_BORD),
            Portee.SON_PORTEFEUILLE),

    TRAVAUX("Responsable travaux", "Traite les interventions et suit les chantiers",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.TRAVAUX_LIRE, Droit.TRAVAUX_ECRIRE, Droit.TRAVAUX_VALIDER,
                    Droit.TABLEAU_BORD),
            Portee.TOUT),

    EDITEUR("Éditeur web", "Pages, articles et validation des annonces",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.ANNONCE_VALIDER, Droit.CONTENU_ECRIRE, Droit.DEMANDE_LIRE),
            Portee.TOUT),

    DIRECTION("Direction", "Vue d'ensemble du patrimoine et des indicateurs",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.PATRIMOINE_ECRIRE, Droit.LOCATION_LIRE, Droit.LOCATION_ECRIRE,
                    Droit.TRAVAUX_LIRE, Droit.ANNONCE_VALIDER, Droit.AFFECTATION_LIRE, Droit.AFFECTATION_ECRIRE,
                    Droit.DEMANDE_LIRE, Droit.DEMANDE_ECRIRE, Droit.TABLEAU_BORD, Droit.PARAMETRES_LIRE),
            Portee.TOUT),

    ADMIN("Administrateur", "Comptes, droits et listes de référence",
            Set.of(Droit.PATRIMOINE_LIRE, Droit.PATRIMOINE_ECRIRE, Droit.LOCATION_LIRE, Droit.LOCATION_ECRIRE,
                    Droit.ENCAISSEMENT_ECRIRE, Droit.TRAVAUX_LIRE, Droit.TRAVAUX_ECRIRE,
                    Droit.ANNONCE_VALIDER, Droit.CONTENU_ECRIRE, Droit.AFFECTATION_LIRE,
                    Droit.AFFECTATION_ECRIRE, Droit.DEMANDE_LIRE, Droit.DEMANDE_ECRIRE, Droit.TABLEAU_BORD,
                    Droit.PARAMETRES_LIRE, Droit.PARAMETRES_ECRIRE, Droit.COMPTES),
            Portee.TOUT);

    /** Jusqu'où va le regard d'un profil sur le patrimoine. */
    public enum Portee {
        SES_BIENS, SON_PORTEFEUILLE, TOUT
    }

    /** Les droits fins nommés dans la matrice du § 2.2. */
    public static final class Droit {
        public static final String PATRIMOINE_LIRE = "patrimoine.lire";
        public static final String PATRIMOINE_ECRIRE = "patrimoine.ecrire";
        public static final String AFFECTATION_LIRE = "affectation.lire";
        public static final String AFFECTATION_ECRIRE = "affectation.ecrire";
        public static final String LOCATION_LIRE = "location.lire";
        public static final String LOCATION_ECRIRE = "location.ecrire";
        public static final String ENCAISSEMENT_ECRIRE = "encaissement.ecrire";
        public static final String TRAVAUX_LIRE = "travaux.lire";
        public static final String TRAVAUX_ECRIRE = "travaux.ecrire";
        public static final String TRAVAUX_VALIDER = "travaux.valider";
        public static final String ANNONCE_ECRIRE = "annonce.ecrire";
        public static final String ANNONCE_VALIDER = "annonce.valider";
        public static final String CONTENU_ECRIRE = "contenu.ecrire";
        public static final String DEMANDE_LIRE = "demande.lire";
        public static final String DEMANDE_ECRIRE = "demande.ecrire";
        public static final String TABLEAU_BORD = "tableau-bord";
        public static final String PARAMETRES_LIRE = "parametres.lire";
        public static final String PARAMETRES_ECRIRE = "parametres.ecrire";
        public static final String TERRAIN = "terrain";
        public static final String COMPTES = "comptes";

        private Droit() {
        }

        /** Tous les droits, dans l'ordre où l'administration des comptes les présente. */
        public static final List<String> TOUS = List.of(
                PATRIMOINE_LIRE, PATRIMOINE_ECRIRE, AFFECTATION_LIRE, AFFECTATION_ECRIRE,
                LOCATION_LIRE, LOCATION_ECRIRE, ENCAISSEMENT_ECRIRE,
                TRAVAUX_LIRE, TRAVAUX_ECRIRE, TRAVAUX_VALIDER,
                ANNONCE_ECRIRE, ANNONCE_VALIDER, CONTENU_ECRIRE,
                DEMANDE_LIRE, DEMANDE_ECRIRE, TABLEAU_BORD,
                PARAMETRES_LIRE, PARAMETRES_ECRIRE, TERRAIN, COMPTES);
    }

    private final String libelle;
    private final String description;
    private final Set<String> droits;
    private final Portee portee;

    Profil(String libelle, String description, Set<String> droits, Portee portee) {
        this.libelle = libelle;
        this.description = description;
        this.droits = droits;
        this.portee = portee;
    }

    public String libelle() {
        return libelle;
    }

    public String description() {
        return description;
    }

    /** Les droits que ce profil porte d'office. */
    public Set<String> droits() {
        return droits;
    }

    public Portee portee() {
        return portee;
    }

    public static Optional<Profil> parCode(String code) {
        return Arrays.stream(values()).filter(p -> p.name().equals(code)).findFirst();
    }
}
