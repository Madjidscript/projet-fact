package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.CompteFacade;
import com.eburtis.fact.facade.EspaceFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Étape 12 contre la base réelle, dans une transaction annulée : « un propriétaire ne voit que ses
 * biens » (recette du § 9) et répond en ligne aux devis qui dépassent son seuil.
 * Sur demande : {@code set FACT_TESTS_BASE=1}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class EspaceBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);

    @Autowired private ProprietaireFacade proprietaires;
    @Autowired private BatimentFacade batiments;
    @Autowired private CompteFacade comptes;
    @Autowired private EspaceFacade espace;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ObjectMapper json;

    private int proA;
    private int proB;
    private int batA;
    private int batB;
    private Utilisateur awa;

    private int proprietaire(String nom, String email) {
        return proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", nom, null, "0700000000", null, email, null,
                null, null, null, null, null, null), ADMIN).get("id").asInt();
    }

    private int batiment(String nom, int pro) {
        int cocody = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        return batiments.enregistrer(new BatimentSaisieDto(null, nom, "IMMEUBLE", null, cocody, null, 5.35, -3.98, null, 2, null, null,
                pro, null, null), ADMIN).get("id").asInt();
    }

    private int devis(int idBatiment, String reference, int montant) {
        return jdbc.queryForObject("""
                INSERT INTO tra.intervention (reference, id_batiment, categorie, titre, statut, devis_montant)
                VALUES (?, ?, 'PLOMBERIE', 'Zztest fuite', 'EN_COURS', ?) RETURNING id""", Integer.class, reference, idBatiment, montant);
    }

    @BeforeEach
    void preparer() {
        proA = proprietaire("Zztest Awa", "zztest.awa@exemple.ci");
        proB = proprietaire("Zztest Voisin", null);
        batA = batiment("Zztest Immeuble A", proA);
        batB = batiment("Zztest Immeuble B", proB);
        Map<String, Object> acces = proprietaires.ouvrirEspace(proA, ADMIN, comptes);
        awa = new Utilisateur((Integer) acces.get("id"), (String) acces.get("identifiant"), "Zztest Awa", null, "zztest.awa@exemple.ci",
                Profil.PROPRIETAIRE, Set.of(), false, 0);
    }

    @SuppressWarnings("unchecked")
    private List<String> nomsDesBatiments(Map<String, Object> e) {
        return ((List<Map<String, Object>>) json.convertValue(e.get("batiments"), List.class)).stream().map((b) -> (String) b.get("nom")).toList();
    }

    @Test
    @DisplayName("Un propriétaire ne voit que ses biens, même s'il demande l'espace d'un autre")
    void neVoitQueSesBiens() {
        assertEquals(List.of("Zztest Immeuble A"), nomsDesBatiments(espace.espace(awa, null)));
        assertEquals(List.of("Zztest Immeuble A"), nomsDesBatiments(espace.espace(awa, proB)));
        assertEquals(false, espace.espace(awa, null).get("apercu"));
        // La Direction voit l'espace en aperçu ; un gestionnaire n'y a pas accès.
        assertEquals(List.of("Zztest Immeuble B"), nomsDesBatiments(espace.espace(ADMIN, proB)));
        assertEquals(true, espace.espace(ADMIN, proB).get("apercu"));
        assertThrows(ErreurMetier.class, () -> espace.espace(GESTIONNAIRE, proA));
        // Rien sur les locataires : ni nom ni téléphone.
        String texte = json.valueToTree(espace.espace(awa, null)).toString();
        assertFalse(texte.contains("\"locataire\""));
    }

    @Test
    @DisplayName("Le propriétaire accepte ou refuse en ligne un devis au-delà de son seuil, jamais celui d'un autre")
    void repondAuxDevis() {
        int i1 = devis(batA, "ZZT-0001", 400000);
        int i2 = devis(batA, "ZZT-0002", 380000);
        int autre = devis(batB, "ZZT-0003", 500000);
        int petit = devis(batA, "ZZT-0004", 20000);

        assertThrows(ErreurMetier.class, () -> espace.repondre(i2, false, " ", awa), "un refus demande un motif");
        assertThrows(ErreurMetier.class, () -> espace.repondre(autre, true, null, awa), "devis d'un autre propriétaire");
        assertThrows(ErreurMetier.class, () -> espace.repondre(petit, true, null, awa), "devis sous le seuil");
        assertThrows(ErreurMetier.class, () -> espace.repondre(i1, true, null, ADMIN), "seul le propriétaire répond");

        espace.repondre(i1, true, null, awa);
        espace.repondre(i2, false, "Trop cher", awa);
        assertEquals("ACCORD", jdbc.queryForObject("SELECT accord_decision FROM tra.intervention WHERE id = ?", String.class, i1));
        assertTrue(jdbc.queryForObject("SELECT accord_proprietaire FROM tra.intervention WHERE id = ?", Boolean.class, i1));
        assertEquals("Trop cher", jdbc.queryForObject("SELECT accord_motif FROM tra.intervention WHERE id = ?", String.class, i2));
        assertThrows(ErreurMetier.class, () -> espace.repondre(i1, false, "Finalement non", awa), "on ne répond qu'une fois");
        // Le responsable travaux (à défaut la Direction) est prévenu de chaque réponse.
        assertEquals(2, jdbc.queryForObject("SELECT count(DISTINCT cle) FROM app.alerte WHERE type = 'ACCORD_PROPRIETAIRE' AND cle IN (?, ?)",
                Integer.class, i1 + "-ACCORD", i2 + "-REFUS"));
    }

    @Test
    @DisplayName("L'espace s'ouvre une seule fois, et seulement avec un e-mail")
    void ouvertureDeLEspace() {
        assertThrows(ErreurMetier.class, () -> proprietaires.ouvrirEspace(proB, ADMIN, comptes), "sans e-mail");
        assertThrows(ErreurMetier.class, () -> proprietaires.ouvrirEspace(proA, ADMIN, comptes), "déjà ouvert");
        int proC = proprietaire("Zztest Troisième", "zztest.c@exemple.ci");
        assertThrows(ErreurMetier.class, () -> proprietaires.ouvrirEspace(proC, GESTIONNAIRE, comptes), "réservé à la Direction");
        assertEquals("PROPRIETAIRE", jdbc.queryForObject("SELECT profil FROM app.compte WHERE id = ?", String.class, awa.id()));
        assertTrue(jdbc.queryForObject("SELECT doit_changer FROM app.compte WHERE id = ?", Boolean.class, awa.id()));
    }
}
