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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Le dossier des photos, hors de l'application ({@code file.upload.base-dir}/{@code file.upload.photo-dir}),
 * à sauvegarder avec la base. Une photo y est redressée d'après son orientation EXIF, réduite à
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

    private Path racine;

    @PostConstruct
    public void init() {
        racine = Paths.get(baseDir, photoDir).toAbsolutePath().normalize();
        try {
            Files.createDirectories(racine);
        } catch (IOException e) {
            throw new IllegalStateException("Impossible de créer le répertoire des photos : " + racine, e);
        }
        LOG.info("Répertoire des photos : {}", racine);
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
        Path fichier = resoudre(chemin);
        try {
            Files.createDirectories(fichier.getParent());
            Files.write(fichier, jpeg);
        } catch (IOException e) {
            throw new UncheckedIOException("La photo n'a pas pu être écrite dans " + fichier.getParent(), e);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int statut) {
                    if (statut != STATUS_COMMITTED) {
                        try {
                            Files.deleteIfExists(fichier);
                        } catch (IOException e) {
                            LOG.warn("Photo orpheline non effacée : {}", fichier);
                        }
                    }
                }
            });
        }
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
        Path fichier = resoudre(chemin);
        try {
            Files.createDirectories(fichier.getParent());
            Files.write(fichier, contenu);
        } catch (IOException e) {
            throw new UncheckedIOException("Le fichier n'a pas pu être écrit dans " + fichier.getParent(), e);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int statut) {
                    if (statut != STATUS_COMMITTED) {
                        try {
                            Files.deleteIfExists(fichier);
                        } catch (IOException e) {
                            LOG.warn("Fichier orphelin non effacé : {}", fichier);
                        }
                    }
                }
            });
        }
        return chemin;
    }

    public byte[] lire(String chemin) {
        try {
            return Files.readAllBytes(resoudre(chemin));
        } catch (IOException e) {
            throw ErreurMetier.introuvable("Photo introuvable sur le disque.");
        }
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
