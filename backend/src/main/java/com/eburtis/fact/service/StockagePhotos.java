package com.eburtis.fact.service;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

import com.eburtis.fact.exception.ErreurMetier;
import com.eburtis.fact.utils.ImageUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Les photos et pièces jointes. Deux rangements possibles :
 * <ul>
 * <li>un bucket Cloudflare R2 privé, quand {@code fact.stockage.r2.*} est renseigné (en ligne : le
 * disque du serveur est effacé à chaque redémarrage) ; la clé d'un fichier y reprend son chemin
 * sur disque, {@code photos/dossier/objet/uuid.jpg} ;</li>
 * <li>sinon le dossier local ({@code file.upload.base-dir}/{@code file.upload.photo-dir}), à
 * sauvegarder avec la base.</li>
 * </ul>
 * Dans les deux cas, une photo est redressée d'après son orientation EXIF, réduite à
 * 2 048 pixels et réécrite en JPEG, ce qui retire aussi ses métadonnées (dont la position GPS de
 * l'appareil). Elle est rangée sous {@code dossier/objet/uuid.jpg} ; la base ne garde que ce chemin.
 * Un fichier écrit pendant une transaction annulée est effacé : il ne reste pas d'orphelin.
 */
@Component
public class StockagePhotos {

    private static final Logger LOG = LoggerFactory.getLogger(StockagePhotos.class);
    private static final String SEGMENT = "[A-Za-z0-9_-]{1,64}";
    private static final int COTE = 2048;
    private static final long POIDS_MAX = 1_500_000;

    /** Une photo rangée : son chemin et ses dimensions. */
    public record Rangee(String chemin, int largeur, int hauteur, int taille) {
    }

    @Value("${file.upload.base-dir:${user.home}/fact-immobilier/uploads}")
    private String baseDir;

    @Value("${file.upload.photo-dir:photos}")
    private String photoDir;

    @Value("${fact.stockage.r2.endpoint:}")
    private String r2Endpoint;
    @Value("${fact.stockage.r2.bucket:}")
    private String r2Bucket;
    @Value("${fact.stockage.r2.cle-acces:}")
    private String r2CleAcces;
    @Value("${fact.stockage.r2.cle-secrete:}")
    private String r2CleSecrete;

    private Path racine;
    /** Le bucket R2, ou {@code null} : les fichiers vont alors sur le disque. */
    private DepotR2 r2;

    @PostConstruct
    public void init() {
        racine = Paths.get(baseDir, photoDir).toAbsolutePath().normalize();
        if (!DepotR2.blanc(r2Endpoint) || !DepotR2.blanc(r2Bucket)) {
            r2 = new DepotR2(r2Endpoint, r2Bucket, r2CleAcces, r2CleSecrete);
            LOG.info("Photos et pièces jointes : bucket Cloudflare R2 « {} »", r2.bucket());
            return;
        }
        try {
            Files.createDirectories(racine);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de créer le répertoire des photos : " + racine, e);
        }
        LOG.info("Répertoire des photos : {}", racine);
    }

    @PreDestroy
    void fermer() {
        if (r2 != null) {
            r2.close();
        }
    }

    /** Range une photo ; refuse ce qui n'est pas une image lisible (JPEG, PNG, GIF, BMP). */
    public Rangee enregistrer(String dossier, String objet, byte[] contenu) {
        if (!dossier.matches(SEGMENT) || !objet.matches(SEGMENT)) {
            throw new IllegalArgumentException("Chemin de photo invalide : " + dossier + "/" + objet);
        }
        BufferedImage image = ImageUtils.lire(contenu);
        if (image == null) {
            throw ErreurMetier.invalide("Ce fichier n'est pas une photo lisible : envoyez un JPEG ou un PNG.");
        }
        byte[] jpeg = ImageUtils.jpegSous(image, COTE, POIDS_MAX);
        BufferedImage finale = ImageUtils.lire(jpeg);
        String chemin = dossier + "/" + objet + "/" + UUID.randomUUID() + ".jpg";
        ecrire(chemin, jpeg, "image/jpeg");
        return new Rangee(chemin, finale == null ? 0 : finale.getWidth(), finale == null ? 0 : finale.getHeight(), jpeg.length);
    }

    /**
     * Range un fichier tel quel (un plan en PDF joint à une demande de devis) ; même règles de
     * chemin et même effacement si la transaction est annulée.
     */
    public String enregistrerFichier(String dossier, String objet, byte[] contenu, String extension) {
        if (!dossier.matches(SEGMENT) || !objet.matches(SEGMENT) || !extension.matches("[a-z0-9]{2,5}")) {
            throw new IllegalArgumentException("Chemin de fichier invalide : " + dossier + "/" + objet);
        }
        String chemin = dossier + "/" + objet + "/" + UUID.randomUUID() + "." + extension;
        ecrire(chemin, contenu, typeDe(extension));
        return chemin;
    }

    public byte[] lire(String chemin) {
        if (r2 != null) {
            byte[] contenu;
            try {
                contenu = r2.lire(cleR2(chemin));
            } catch (RuntimeException e) {
                LOG.error("Lecture impossible dans le bucket R2 : {}", chemin, e);
                throw ErreurMetier.introuvable("Ce fichier est momentanément indisponible.");
            }
            if (contenu == null) {
                throw ErreurMetier.introuvable("Photo introuvable.");
            }
            return contenu;
        }
        try {
            return Files.readAllBytes(resoudre(chemin));
        } catch (IOException e) {
            throw ErreurMetier.introuvable("Photo introuvable sur le disque.");
        }
    }

    /**
     * Écrit un fichier (bucket ou disque) ; s'il est écrit pendant une transaction ensuite annulée,
     * il est effacé : il ne reste pas d'orphelin.
     */
    private void ecrire(String chemin, byte[] contenu, String type) {
        Runnable effacer;
        if (r2 != null) {
            String cle = cleR2(chemin);
            try {
                r2.ecrire(cle, contenu, type);
            } catch (RuntimeException e) {
                LOG.error("Écriture impossible dans le bucket R2 : {}", cle, e);
                throw new IllegalStateException("Le fichier n'a pas pu être enregistré. Réessayez dans un instant.", e);
            }
            effacer = () -> r2.effacer(cle);
        } else {
            Path fichier = resoudre(chemin);
            try {
                Files.createDirectories(fichier.getParent());
                Files.write(fichier, contenu);
            } catch (IOException e) {
                throw new UncheckedIOException("Le fichier n'a pas pu être écrit dans " + fichier.getParent(), e);
            }
            effacer = () -> {
                try {
                    Files.deleteIfExists(fichier);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            };
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int statut) {
                    if (statut != STATUS_COMMITTED) {
                        try {
                            effacer.run();
                        } catch (RuntimeException e) {
                            LOG.warn("Fichier orphelin non effacé : {}", chemin);
                        }
                    }
                }
            });
        }
    }

    /** Le type d'un fichier d'après son extension. */
    static String typeDe(String extension) {
        return switch (extension.toLowerCase()) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            default -> "application/octet-stream";
        };
    }

    /** La clé d'un fichier dans le bucket : son chemin sur disque, sous le dossier des photos. */
    private String cleR2(String chemin) {
        String c = chemin.replace('\\', '/');
        if (c.startsWith("/") || c.contains("..")) {
            throw new IllegalArgumentException("Chemin de photo invalide : " + chemin);
        }
        return photoDir + "/" + c;
    }

    /** Le fichier d'un chemin, sans jamais sortir du dossier des photos. */
    private Path resoudre(String chemin) {
        Path p = racine.resolve(chemin).normalize();
        if (!p.startsWith(racine)) {
            throw new IllegalArgumentException("Chemin de photo hors du dossier : " + chemin);
        }
        return p;
    }
}
