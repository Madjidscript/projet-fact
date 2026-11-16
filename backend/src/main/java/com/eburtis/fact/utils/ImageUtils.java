package com.eburtis.fact.utils;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * Traitement des photos envoyées depuis un téléphone ou un poste : lecture, redressement d'après
 * l'orientation EXIF, réduction, et réécriture en JPEG (ce qui retire aussi les métadonnées, dont
 * la position GPS de l'appareil).
 */
public final class ImageUtils {

    private ImageUtils() {
    }

    /** L'image décodée, ou null si le contenu n'est pas une image lisible (HEIC, PDF…). */
    public static BufferedImage lire(byte[] contenu) {
        try {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(contenu));
            return img == null ? null : redresser(img, orientation(contenu));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Réduite pour que son plus grand côté ne dépasse pas {@code cote} pixels, sur fond blanc. */
    public static BufferedImage reduire(BufferedImage img, int cote) {
        double ratio = Math.min(1.0, (double) cote / Math.max(img.getWidth(), img.getHeight()));
        int l = Math.max(1, (int) Math.round(img.getWidth() * ratio));
        int h = Math.max(1, (int) Math.round(img.getHeight() * ratio));
        BufferedImage r = new BufferedImage(l, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = r.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, l, h);
        g.drawImage(img, 0, 0, l, h, null);
        g.dispose();
        return r;
    }

    /**
     * Le JPEG de la meilleure qualité possible qui tient dans {@code limite} octets : le plus grand
     * côté ne dépasse pas {@code cote} pixels. Si le fichier reste trop lourd, la compression
     * augmente un peu, puis l'image est réduite d'après l'écart constaté (la surface d'une image
     * suit à peu près le poids de son JPEG) : deux ou trois passages suffisent en général.
     */
    public static byte[] jpegSous(BufferedImage img, int cote, long limite) {
        BufferedImage courante = reduire(img, cote);
        byte[] b = jpeg(courante, 0.92f);
        if (b.length <= limite) {
            return b;
        }
        b = jpeg(courante, 0.86f);
        while (b.length > limite) {
            int plusGrand = Math.max(courante.getWidth(), courante.getHeight());
            if (plusGrand <= 640) {
                return jpeg(courante, 0.75f);
            }
            double facteur = Math.min(0.9, Math.sqrt((double) limite / b.length) * 0.95);
            courante = reduire(courante, (int) Math.round(plusGrand * facteur));
            b = jpeg(courante, 0.86f);
        }
        return b;
    }

    public static byte[] jpeg(BufferedImage img, float qualite) {
        Iterator<ImageWriter> ecrivains = ImageIO.getImageWritersByFormatName("jpeg");
        ImageWriter w = ecrivains.next();
        ByteArrayOutputStream sortie = new ByteArrayOutputStream();
        try (ImageOutputStream flux = ImageIO.createImageOutputStream(sortie)) {
            w.setOutput(flux);
            ImageWriteParam p = w.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(qualite);
            w.write(null, new IIOImage(img, null, null), p);
        } catch (IOException e) {
            throw new IllegalStateException("Écriture JPEG impossible", e);
        } finally {
            w.dispose();
        }
        return sortie.toByteArray();
    }

    // ------------------------------------------------------------------ orientation EXIF
    private static BufferedImage redresser(BufferedImage img, int orientation) {
        int quarts = switch (orientation) {
            case 3 -> 2;
            case 6 -> 1;
            case 8 -> 3;
            default -> 0;
        };
        if (quarts == 0) {
            return img;
        }
        int l = img.getWidth();
        int h = img.getHeight();
        boolean couche = quarts % 2 == 1;
        BufferedImage r = new BufferedImage(couche ? h : l, couche ? l : h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = r.createGraphics();
        AffineTransform t = new AffineTransform();
        switch (quarts) {
            case 1 -> { t.translate(h, 0); t.rotate(Math.PI / 2); }
            case 2 -> { t.translate(l, h); t.rotate(Math.PI); }
            default -> { t.translate(0, l); t.rotate(-Math.PI / 2); }
        }
        g.drawImage(img, t, null);
        g.dispose();
        return r;
    }

    /** L'orientation EXIF d'un JPEG (1 à 8), 1 si absente ou illisible. */
    public static int orientation(byte[] b) {
        try {
            if (b.length < 4 || (b[0] & 0xFF) != 0xFF || (b[1] & 0xFF) != 0xD8) {
                return 1;
            }
            int i = 2;
            while (i + 4 < b.length) {
                if ((b[i] & 0xFF) != 0xFF) {
                    return 1;
                }
                int marqueur = b[i + 1] & 0xFF;
                int longueur = ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
                if (marqueur == 0xE1 && i + 10 < b.length && b[i + 4] == 'E' && b[i + 5] == 'x' && b[i + 6] == 'i' && b[i + 7] == 'f') {
                    return orientationTiff(b, i + 10);
                }
                if (marqueur == 0xDA) {
                    return 1;            // début des données image : pas d'EXIF avant
                }
                i += 2 + longueur;
            }
        } catch (RuntimeException ignore) {
            // EXIF tronqué : on garde l'image telle quelle
        }
        return 1;
    }

    private static int orientationTiff(byte[] b, int tiff) {
        boolean petit = b[tiff] == 'I';
        int ifd = tiff + lire32(b, tiff + 4, petit);
        int n = lire16(b, ifd, petit);
        for (int k = 0; k < n; k++) {
            int e = ifd + 2 + k * 12;
            if (lire16(b, e, petit) == 0x0112) {
                int v = lire16(b, e + 8, petit);
                return v >= 1 && v <= 8 ? v : 1;
            }
        }
        return 1;
    }

    private static int lire16(byte[] b, int i, boolean petit) {
        return petit ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) : ((b[i] & 0xFF) << 8) | (b[i + 1] & 0xFF);
    }

    private static int lire32(byte[] b, int i, boolean petit) {
        return petit
                ? (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24)
                : ((b[i] & 0xFF) << 24) | ((b[i + 1] & 0xFF) << 16) | ((b[i + 2] & 0xFF) << 8) | (b[i + 3] & 0xFF);
    }
}
