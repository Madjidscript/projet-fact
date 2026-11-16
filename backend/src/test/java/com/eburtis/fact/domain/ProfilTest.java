package com.eburtis.fact.domain;

import static com.eburtis.fact.domain.Profil.ADMIN;
import static com.eburtis.fact.domain.Profil.AGENT;
import static com.eburtis.fact.domain.Profil.DIRECTION;
import static com.eburtis.fact.domain.Profil.EDITEUR;
import static com.eburtis.fact.domain.Profil.GESTIONNAIRE;
import static com.eburtis.fact.domain.Profil.PROPRIETAIRE;
import static com.eburtis.fact.domain.Profil.TRAVAUX;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import com.eburtis.fact.domain.Profil.Droit;
import com.eburtis.fact.domain.Profil.Portee;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La matrice des droits du § 2.2 des spécifications, ligne par ligne. Colonnes de la matrice :
 * Propriétaire, Agent, Gestionnaire, Travaux, Direction, Admin. « L » lecture, « E » création
 * et modification, « V » validation, « - » aucun accès.
 */
class ProfilTest {

    /** Vérifie qu'un droit est porté par ces profils, et par aucun autre. */
    private static void seuls(String droit, Profil... attendus) {
        Set<Profil> oui = Set.of(attendus);
        for (Profil p : Profil.values()) {
            assertEquals(oui.contains(p), p.droits().contains(droit), p + " / " + droit);
        }
    }

    @Test
    @DisplayName("Bâtiments et lots : L (ses) · E · E (ses) · L · E · E")
    void batimentsEtLots() {
        seuls(Droit.PATRIMOINE_LIRE, PROPRIETAIRE, AGENT, GESTIONNAIRE, TRAVAUX, EDITEUR, DIRECTION, ADMIN);
        seuls(Droit.PATRIMOINE_ECRIRE, AGENT, GESTIONNAIRE, DIRECTION, ADMIN);
    }

    @Test
    @DisplayName("Affectation des gestionnaires : - · - · L · - · E · E")
    void affectation() {
        seuls(Droit.AFFECTATION_LIRE, GESTIONNAIRE, DIRECTION, ADMIN);
        seuls(Droit.AFFECTATION_ECRIRE, DIRECTION, ADMIN);
    }

    @Test
    @DisplayName("Occupants et baux : L (ses) · L · E (ses) · - · E · E")
    void occupantsEtBaux() {
        seuls(Droit.LOCATION_LIRE, PROPRIETAIRE, AGENT, GESTIONNAIRE, DIRECTION, ADMIN);
        seuls(Droit.LOCATION_ECRIRE, GESTIONNAIRE, DIRECTION, ADMIN);
    }

    @Test
    @DisplayName("Encaissements et impayés : L (ses) · E · E (ses) · - · L · E")
    void encaissements() {
        seuls(Droit.ENCAISSEMENT_ECRIRE, AGENT, GESTIONNAIRE, ADMIN);
        assertFalse(DIRECTION.droits().contains(Droit.ENCAISSEMENT_ECRIRE), "la Direction lit les encaissements, sans les saisir");
    }

    @Test
    @DisplayName("Interventions : L (ses) · E · E (ses) · E, V · L · E")
    void interventions() {
        seuls(Droit.TRAVAUX_LIRE, PROPRIETAIRE, AGENT, GESTIONNAIRE, TRAVAUX, DIRECTION, ADMIN);
        seuls(Droit.TRAVAUX_ECRIRE, AGENT, GESTIONNAIRE, TRAVAUX, ADMIN);
        seuls(Droit.TRAVAUX_VALIDER, TRAVAUX);
    }

    @Test
    @DisplayName("Publication sur le site : - · - · E · - · V · V (et l'éditeur web valide, § 4.10)")
    void publication() {
        seuls(Droit.ANNONCE_ECRIRE, GESTIONNAIRE);
        seuls(Droit.ANNONCE_VALIDER, EDITEUR, DIRECTION, ADMIN);
    }

    @Test
    @DisplayName("Tableaux de bord : ses biens · - · son portefeuille · travaux · tout · tout")
    void tableauxDeBord() {
        seuls(Droit.TABLEAU_BORD, PROPRIETAIRE, GESTIONNAIRE, TRAVAUX, DIRECTION, ADMIN);
        assertEquals(Portee.SES_BIENS, PROPRIETAIRE.portee());
        assertEquals(Portee.SON_PORTEFEUILLE, GESTIONNAIRE.portee());
        assertEquals(Portee.TOUT, DIRECTION.portee());
        assertEquals(Portee.TOUT, ADMIN.portee());
    }

    @Test
    @DisplayName("Comptes et paramétrage : - · - · - · - · L · E")
    void comptesEtParametrage() {
        seuls(Droit.PARAMETRES_LIRE, DIRECTION, ADMIN);
        seuls(Droit.PARAMETRES_ECRIRE, ADMIN);
        seuls(Droit.COMPTES, ADMIN);
    }

    @Test
    @DisplayName("Le propriétaire ne peut rien écrire ni valider")
    void proprietaireEnLectureSeule() {
        for (String droit : PROPRIETAIRE.droits()) {
            assertFalse(droit.endsWith(".ecrire") || droit.endsWith(".valider"), droit);
        }
    }

    @Test
    @DisplayName("Tous les droits portés par un profil sont des droits connus")
    void droitsConnus() {
        for (Profil p : Profil.values()) {
            assertTrue(Droit.TOUS.containsAll(p.droits()), p.name());
        }
    }

    @Test
    @DisplayName("Un droit accordé en plus s'ajoute à ceux du profil, sans rien lui retirer")
    void droitAccordeEnPlus() {
        Utilisateur agent = new Utilisateur(1, "agent.test", "Agent de test", null, null, AGENT,
                Set.of(Droit.TABLEAU_BORD), false, 0);
        assertTrue(agent.peut(Droit.TABLEAU_BORD));
        assertTrue(agent.peut(Droit.PATRIMOINE_ECRIRE));
        assertFalse(agent.peut(Droit.COMPTES));
        assertFalse(agent.estDirection());
    }
}
