package com.eburtis.fact.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.stream.Stream;

/**
 * Copie, une fois, le dossier local des fichiers (photos, signatures, pièces jointes) dans le
 * bucket Cloudflare R2, sous les mêmes chemins : les fiches de la base les retrouvent telles
 * quelles. Un fichier déjà présent dans le bucket n'est pas renvoyé ; on peut relancer sans risque.
 *
 * <p>Lancement : {@code gradlew envoyerFichiersR2} (ou envoyer-photos-r2.bat), avec R2_ENDPOINT,
 * R2_BUCKET, R2_CLE_ACCES et R2_CLE_SECRETE dans l'environnement. Les clés ne sont écrites nulle part.</p>
 */
public final class EnvoiFichiersR2 {

    private EnvoiFichiersR2() {
    }

    public static void main(String[] args) throws IOException {
        Path racine = Paths.get(args.length > 0 ? args[0] : System.getProperty("user.home") + "/fact-immobilier/uploads")
                .toAbsolutePath().normalize();
        if (!Files.isDirectory(racine)) {
            System.out.println("Dossier introuvable : " + racine);
            System.exit(1);
        }
        List<Path> fichiers;
        try (Stream<Path> s = Files.walk(racine)) {
            fichiers = s.filter(Files::isRegularFile).sorted().toList();
        }
        System.out.println(fichiers.size() + " fichier(s) dans " + racine);
        int envoyes = 0;
        int deja = 0;
        int echecs = 0;
        try (DepotR2 r2 = new DepotR2(System.getenv("R2_ENDPOINT"), System.getenv("R2_BUCKET"),
                System.getenv("R2_CLE_ACCES"), System.getenv("R2_CLE_SECRETE"))) {
            for (Path f : fichiers) {
                String cle = racine.relativize(f).toString().replace('\\', '/');
                try {
                    if (r2.existe(cle)) {
                        deja++;
                        continue;
                    }
                    String nom = f.getFileName().toString();
                    String extension = nom.contains(".") ? nom.substring(nom.lastIndexOf('.') + 1) : "";
                    r2.ecrire(cle, Files.readAllBytes(f), StockagePhotos.typeDe(extension));
                    envoyes++;
                    System.out.println("  envoyé : " + cle);
                } catch (RuntimeException e) {
                    echecs++;
                    System.out.println("  ÉCHEC : " + cle + " (" + e.getMessage() + ")");
                }
            }
        }
        System.out.println();
        System.out.println("Envoyés : " + envoyes + " · déjà dans le bucket : " + deja + " · échecs : " + echecs);
        if (echecs > 0) {
            System.exit(2);
        }
    }
}
