package com.eburtis.fact.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.eburtis.fact.configuration.FactProperties;
import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.service.CompteService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Jetons de session, limiteur d'essais et politique des mots de passe. Aucun de ces tests ne touche la base. */
class SecuriteTest {

    private static JwtTokenUtils jetons(String secret) {
        return new JwtTokenUtils(new FactProperties(new FactProperties.Jwt(secret, 12), null,
                new FactProperties.Cors(List.of()), new FactProperties.Carte(5.345, -4.012, 12)));
    }

    private static final String SECRET = "secret-de-test-de-plus-de-trente-deux-caracteres";

    @Test
    @DisplayName("Un jeton émis se relit : identifiant et version de session")
    void jetonAllerRetour() {
        JwtTokenUtils j = jetons(SECRET);
        JwtTokenUtils.Contenu c = j.lire(j.emettre("admin", 3).valeur());
        assertNotNull(c);
        assertEquals("admin", c.identifiant());
        assertEquals(3, c.version());
    }

    @Test
    @DisplayName("Un jeton falsifié, ou signé par une autre clé, est refusé")
    void jetonFalsifie() {
        JwtTokenUtils j = jetons(SECRET);
        String valeur = j.emettre("admin", 0).valeur();
        assertNull(j.lire(valeur.substring(0, valeur.length() - 3) + "abc"));
        assertNull(jetons("une-autre-cle-de-plus-de-trente-deux-caracteres").lire(valeur));
        assertNull(j.lire("ceci-n-est-pas-un-jeton"));
    }

    @Test
    @DisplayName("Un secret de moins de 32 caractères empêche le démarrage")
    void secretTropCourt() {
        assertThrows(IllegalStateException.class, () -> jetons("trop-court"));
    }

    @Test
    @DisplayName("Cinq essais manqués bloquent le poste ; une réussite le débloque")
    void limiteur() {
        LimiteurConnexion l = new LimiteurConnexion();
        for (int i = 0; i < 4; i++) {
            l.echec("10.0.0.1");
        }
        assertFalse(l.bloque("10.0.0.1"));
        l.echec("10.0.0.1");
        assertTrue(l.bloque("10.0.0.1"));
        assertFalse(l.bloque("10.0.0.2"), "un autre poste n'est pas touché");
        l.reussite("10.0.0.1");
        assertFalse(l.bloque("10.0.0.1"));
    }

    @Test
    @DisplayName("Politique des mots de passe : 10 caractères, lettres et chiffres, sans l'identifiant")
    void robustesse() {
        assertThrows(ErreurMetier.class, () -> CompteService.controlerRobustesse(null, "awa.kone"));
        assertThrows(ErreurMetier.class, () -> CompteService.controlerRobustesse("court1", "awa.kone"));
        assertThrows(ErreurMetier.class, () -> CompteService.controlerRobustesse("quedeslettresici", "awa.kone"));
        assertThrows(ErreurMetier.class, () -> CompteService.controlerRobustesse("12345678901234", "awa.kone"));
        assertThrows(ErreurMetier.class, () -> CompteService.controlerRobustesse("Awa.Kone2026ok", "awa.kone"));
        assertThrows(ErreurMetier.class, () -> CompteService.controlerRobustesse("a1".repeat(40), "awa.kone"));
        assertDoesNotThrow(() -> CompteService.controlerRobustesse("Riviera3-Allabra", "awa.kone"));
    }

    @Test
    @DisplayName("Un mot de passe provisoire respecte la politique")
    void provisoire() {
        for (int i = 0; i < 200; i++) {
            String mdp = CompteService.motDePasseProvisoire();
            assertEquals(12, mdp.length());
            assertDoesNotThrow(() -> CompteService.controlerRobustesse(mdp, "admin"), mdp);
        }
    }
}
