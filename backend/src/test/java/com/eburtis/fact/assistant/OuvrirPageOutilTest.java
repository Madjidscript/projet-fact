package com.eburtis.fact.assistant;

import java.util.Set;

import com.eburtis.fact.assistant.outils.OuvrirPageOutil;
import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Le guide n'ouvre que les pages que le compte a le droit d'ouvrir. */
class OuvrirPageOutilTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    // Sans BatimentFacade : ces tests n'ouvrent pas de fiche de bâtiment.
    private static final OuvrirPageOutil OUTIL = new OuvrirPageOutil(null);

    private static Utilisateur compte(Profil profil) {
        return new Utilisateur(1, "test", "Test", null, "t@fact.test", profil, Set.of(), false, 1);
    }

    private static JsonNode page(String cle) throws Exception {
        return JSON.readTree("{\"page\":\"" + cle + "\"}");
    }

    @Test
    void unePagePermise_laisseUneConsigneDeNavigationPourAngular() throws Exception {
        ActionsPage actions = new ActionsPage();

        String resultat = OUTIL.executer(page("loyers"), compte(Profil.GESTIONNAIRE), actions);

        assertThat(resultat).contains("est ouverte");
        assertThat(actions.toutes()).hasSize(1);
        assertThat(actions.toutes().get(0)).containsEntry("chemin", "/loyers");
    }

    @Test
    void unePageSansLeDroit_estRefusee_etRienNEstOuvert() throws Exception {
        ActionsPage actions = new ActionsPage();

        String resultat = OUTIL.executer(page("journal"), compte(Profil.AGENT), actions);

        assertThat(resultat).startsWith("Refusé");
        assertThat(actions.toutes()).isEmpty();
    }

    @Test
    void unProprietaire_nOuvrePasLesPagesDuPersonnel() throws Exception {
        ActionsPage actions = new ActionsPage();

        String resultat = OUTIL.executer(page("batiments"), compte(Profil.PROPRIETAIRE), actions);

        assertThat(resultat).startsWith("Refusé").contains("Mon espace");
        assertThat(actions.toutes()).isEmpty();
    }

    @Test
    void unePageInventeeParLeModele_estRefusee() throws Exception {
        ActionsPage actions = new ActionsPage();

        String resultat = OUTIL.executer(page("supprimer_tout"), compte(Profil.ADMIN), actions);

        assertThat(resultat).contains("n'existe pas");
        assertThat(actions.toutes()).isEmpty();
    }
}
