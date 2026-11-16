package com.eburtis.fact.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Les montants en toutes lettres des quittances. */
class MontantEnLettresTest {

    @Test
    @DisplayName("Montants courants des loyers d'Abidjan")
    void loyers() {
        assertEquals("trois cent soixante-quinze mille", MontantEnLettres.ecrire(375_000));
        assertEquals("deux cent quatre-vingt-cinq mille", MontantEnLettres.ecrire(285_000));
        assertEquals("deux millions cinq cent mille", MontantEnLettres.ecrire(2_500_000));
        assertEquals("un million", MontantEnLettres.ecrire(1_000_000));
        assertEquals("cent vingt mille", MontantEnLettres.ecrire(120_000));
    }

    @Test
    @DisplayName("Accords de cent, vingt, mille et million")
    void accords() {
        assertEquals("deux cents", MontantEnLettres.ecrire(200));
        assertEquals("deux cent un", MontantEnLettres.ecrire(201));
        assertEquals("deux cent mille", MontantEnLettres.ecrire(200_000));
        assertEquals("quatre-vingts", MontantEnLettres.ecrire(80));
        assertEquals("quatre-vingt mille", MontantEnLettres.ecrire(80_000));
        assertEquals("quatre-vingt-un", MontantEnLettres.ecrire(81));
        assertEquals("quatre-vingts millions", MontantEnLettres.ecrire(80_000_000));
        assertEquals("mille", MontantEnLettres.ecrire(1000));
        assertEquals("deux mille", MontantEnLettres.ecrire(2000));
    }

    @Test
    @DisplayName("Soixante-dix, quatre-vingt-dix, et « et un »")
    void dizaines() {
        assertEquals("vingt et un", MontantEnLettres.ecrire(21));
        assertEquals("soixante et onze", MontantEnLettres.ecrire(71));
        assertEquals("soixante-dix-sept", MontantEnLettres.ecrire(77));
        assertEquals("quatre-vingt-onze", MontantEnLettres.ecrire(91));
        assertEquals("quatre-vingt-dix-neuf", MontantEnLettres.ecrire(99));
        assertEquals("dix-sept", MontantEnLettres.ecrire(17));
        assertEquals("zéro", MontantEnLettres.ecrire(0));
        assertEquals("un milliard deux cent trente-quatre millions cinq cent soixante-sept mille huit cent quatre-vingt-dix",
                MontantEnLettres.ecrire(1_234_567_890));
    }
}
