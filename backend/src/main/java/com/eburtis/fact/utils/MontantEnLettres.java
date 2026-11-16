package com.eburtis.fact.utils;

/**
 * Un montant écrit en toutes lettres, en français, comme sur une quittance :
 * 375 000 → « trois cent soixante-quinze mille ». Orthographe traditionnelle, celle des actes :
 * traits d'union sous cent sauf autour de « et » (vingt et un), « cent » et « vingt » au pluriel
 * quand ils terminent le nombre, « mille » invariable, « million » et « milliard » accordés.
 */
public final class MontantEnLettres {

    private static final String[] UNITES = {"zéro", "un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf",
            "dix", "onze", "douze", "treize", "quatorze", "quinze", "seize", "dix-sept", "dix-huit", "dix-neuf"};
    private static final String[] DIZAINES = {"", "", "vingt", "trente", "quarante", "cinquante", "soixante", "soixante",
            "quatre-vingt", "quatre-vingt"};

    private MontantEnLettres() {
    }

    public static String ecrire(long n) {
        if (n < 0) {
            return "moins " + ecrire(-n);
        }
        if (n == 0) {
            return "zéro";
        }
        StringBuilder r = new StringBuilder();
        long milliards = n / 1_000_000_000L;
        long millions = (n / 1_000_000L) % 1000;
        long milliers = (n / 1000) % 1000;
        long reste = n % 1000;
        if (milliards > 0) {
            r.append(centaines((int) milliards, false)).append(milliards > 1 ? " milliards" : " milliard");
        }
        if (millions > 0) {
            espace(r).append(centaines((int) millions, false)).append(millions > 1 ? " millions" : " million");
        }
        if (milliers > 0) {
            espace(r).append(milliers == 1 ? "mille" : centaines((int) milliers, true) + " mille");
        }
        if (reste > 0) {
            espace(r).append(centaines((int) reste, false));
        }
        return r.toString();
    }

    /** De 1 à 999. {@code devantMille} : « deux cent mille », sans « s » à cent ni à quatre-vingt. */
    private static String centaines(int n, boolean devantMille) {
        int c = n / 100;
        int d = n % 100;
        StringBuilder r = new StringBuilder();
        if (c > 0) {
            if (c > 1) {
                r.append(UNITES[c]).append(' ');
            }
            r.append("cent");
            if (c > 1 && d == 0 && !devantMille) {
                r.append('s');
            }
        }
        if (d > 0) {
            espace(r).append(dizaines(d, devantMille));
        }
        return r.toString();
    }

    private static String dizaines(int n, boolean devantMille) {
        if (n < 20) {
            return UNITES[n];
        }
        int d = n / 10;
        int u = n % 10;
        if (d == 7 || d == 9) {
            // soixante-dix… soixante-dix-neuf, quatre-vingt-dix… quatre-vingt-dix-neuf
            return DIZAINES[d] + (d == 7 && u == 1 ? " et " : "-") + UNITES[10 + u];
        }
        if (u == 0) {
            return d == 8 && !devantMille ? "quatre-vingts" : DIZAINES[d];
        }
        if (u == 1 && d != 8) {
            return DIZAINES[d] + " et un";
        }
        return DIZAINES[d] + "-" + UNITES[u];
    }

    private static StringBuilder espace(StringBuilder r) {
        if (!r.isEmpty()) {
            r.append(' ');
        }
        return r;
    }
}
