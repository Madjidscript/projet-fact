package com.eburtis.fact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.eburtis.fact.domain.Profil;
import com.eburtis.fact.domain.Utilisateur;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.facade.AffectationFacade;
import com.eburtis.fact.facade.BatimentFacade;
import com.eburtis.fact.facade.ProprietaireFacade;
import com.eburtis.fact.presentation.dto.BatimentSaisieDto;
import com.eburtis.fact.presentation.dto.ProprietaireSaisieDto;
import com.eburtis.fact.presentation.dto.ReaffectationDto;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * Étape 7 contre la base réelle, dans une transaction annulée : le transfert de cinq bâtiments
 * d'un gestionnaire à un autre (recette du § 9). Sur demande : {@code set FACT_TESTS_BASE=1}.
 */
@SpringBootTest
@Transactional
@EnabledIfEnvironmentVariable(named = "FACT_TESTS_BASE", matches = "1")
class AffectationBaseTest {

    private static final Utilisateur ADMIN = new Utilisateur(-1, "test.admin", "Test Admin", null, null, Profil.ADMIN, Set.of(), false, 0);
    private static final Utilisateur GESTIONNAIRE = new Utilisateur(-2, "test.gest", "Test Gestionnaire", null, null, Profil.GESTIONNAIRE, Set.of(), false, 0);

    @Autowired private ProprietaireFacade proprietaires;
    @Autowired private BatimentFacade batiments;
    @Autowired private AffectationFacade affectations;
    @Autowired private JdbcTemplate jdbc;

    private int g1;
    private int g2;
    private final List<Integer> ids = new ArrayList<>();

    private int compte(String identifiant) {
        return jdbc.queryForObject("""
                INSERT INTO app.compte (identifiant, nom, profil, mot_de_passe, actif, doit_changer)
                VALUES (?, ?, 'GESTIONNAIRE', 'x', true, false) RETURNING id""", Integer.class, identifiant, "Zztest " + identifiant);
    }

    @BeforeEach
    void preparer() {
        g1 = compte("zztest.g1");
        g2 = compte("zztest.g2");
        int cocody = jdbc.queryForObject("SELECT id_commune FROM adm.commune WHERE code = 'COCODY'", Integer.class);
        int pro = proprietaires.enregistrer(new ProprietaireSaisieDto(null, "PHYSIQUE", "Zztest Bailleur", null, null, null, null, null,
                null, null, null, null, null, null), ADMIN).get("id").asInt();
        for (int i = 0; i < 6; i++) {
            ids.add(batiments.enregistrer(new BatimentSaisieDto(null, "Zztest Immeuble " + i, "IMMEUBLE", null, cocody, null, 5.35 + i / 1000.0,
                    -3.98, null, 2, null, null, pro, g1, null), ADMIN).get("id").asInt());
        }
    }

    private int gestionnaire(int idBatiment) {
        return jdbc.queryForObject("SELECT id_gestionnaire FROM pat.batiment WHERE id = ?", Integer.class, idBatiment);
    }

    @Test
    @DisplayName("Cinq bâtiments passent d'un gestionnaire à l'autre : portefeuilles, historique, alerte")
    void transfertDeCinqBatiments() {
        Map<String, Object> r = affectations.reaffecter(new ReaffectationDto(ids.subList(0, 5), g2, false, null, "Départ en congé"), ADMIN);
        assertEquals(5, r.get("modifies"));
        for (int i = 0; i < 5; i++) {
            assertEquals(g2, gestionnaire(ids.get(i)));
        }
        assertEquals(g1, gestionnaire(ids.get(5)), "le sixième reste à son gestionnaire");
        String operation = (String) r.get("operation");
        assertEquals(5, jdbc.queryForObject("""
                SELECT count(*) FROM pat.affectation WHERE operation = ?::uuid AND ancien_gestionnaire = ? AND nouveau_gestionnaire = ?
                  AND motif = 'Départ en congé' AND par IS NOT NULL""", Integer.class, operation, g1, g2));
        assertEquals(1, jdbc.queryForObject("SELECT count(*) FROM app.alerte WHERE id_compte = ? AND type = 'AFFECTATION'", Integer.class, g2),
                "le nouveau gestionnaire est prévenu une fois");
        // Rejouer la même réaffectation ne change rien
        assertEquals(0, affectations.reaffecter(new ReaffectationDto(ids.subList(0, 5), g2, false, null, null), ADMIN).get("modifies"));
    }

    @Test
    @DisplayName("Suppléant, retrait du gestionnaire et contrôles")
    void suppleantEtControles() {
        affectations.reaffecter(new ReaffectationDto(List.of(ids.get(0)), g1, true, g2, null), ADMIN);
        assertEquals(g2, jdbc.queryForObject("SELECT id_suppleant FROM pat.batiment WHERE id = ?", Integer.class, ids.get(0)));
        // Le suppléant devient gestionnaire : il ne peut plus être son propre suppléant
        affectations.reaffecter(new ReaffectationDto(List.of(ids.get(0)), g2, false, null, null), ADMIN);
        assertNull(jdbc.queryForObject("SELECT id_suppleant FROM pat.batiment WHERE id = ?", Integer.class, ids.get(0)));
        // Sans gestionnaire
        affectations.reaffecter(new ReaffectationDto(List.of(ids.get(1)), null, false, null, null), ADMIN);
        assertNull(jdbc.queryForObject("SELECT id_gestionnaire FROM pat.batiment WHERE id = ?", Integer.class, ids.get(1)));

        assertThrows(ErreurMetier.class, () -> affectations.reaffecter(new ReaffectationDto(List.of(ids.get(2)), g2, false, null, null),
                GESTIONNAIRE), "seule la Direction réaffecte");
        assertThrows(ErreurMetier.class, () -> affectations.reaffecter(new ReaffectationDto(List.of(), g2, false, null, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> affectations.reaffecter(new ReaffectationDto(List.of(ids.get(2)), g2, true, g2, null), ADMIN));
        assertThrows(ErreurMetier.class, () -> affectations.reaffecter(new ReaffectationDto(List.of(-99), g2, false, null, null), ADMIN));
    }
}
