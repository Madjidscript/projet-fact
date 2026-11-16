package com.eburtis.fact.utils;

import java.awt.Color;
import java.util.Locale;

import com.lowagie.text.Chunk;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfWriter;

/** La mise en page commune des documents PDF de l'agence : couleurs, polices, en-tête, tableaux. */
public final class Pdf {

    public static final Color NUIT = new Color(0x1A, 0x3F, 0x7A);
    public static final Color OCRE = new Color(0x23, 0x53, 0xA6);   // bleu roi du logo (nom gardé)
    public static final Color GRIS = new Color(0x6C, 0x75, 0x83);
    public static final Color FILET = new Color(0xE2, 0xE5, 0xEB);
    public static final Color FOND = new Color(0xF5, 0xF6, 0xF8);
    public static final Color BON = new Color(0x2E, 0x9E, 0x6B);
    public static final Color CRITIQUE = new Color(0xD6, 0x45, 0x45);

    private Pdf() {
    }

    public static Font police(String nom, float taille, Color couleur) {
        try {
            return new Font(BaseFont.createFont(nom, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED), taille, Font.NORMAL, couleur);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public static Font normal(float taille) {
        return police(BaseFont.HELVETICA, taille, Color.BLACK);
    }

    public static Font gras(float taille, Color couleur) {
        return police(BaseFont.HELVETICA_BOLD, taille, couleur);
    }

    public static Font discret(float taille) {
        return police(BaseFont.HELVETICA, taille, GRIS);
    }

    /** L'en-tête de l'agence à gauche, le titre du document et ses mentions à droite, puis un filet ocre. */
    public static void entete(Document doc, String titre, String... mentions) throws DocumentException {
        PdfPTable t = new PdfPTable(new float[]{1.2f, 1f});
        t.setWidthPercentage(100);
        PdfPCell agence = cellule(null);
        Paragraph nom = new Paragraph();
        nom.add(new Chunk("FACT ", gras(15, OCRE)));
        nom.add(new Chunk("Immobilier", gras(15, NUIT)));
        agence.addElement(nom);
        agence.addElement(new Paragraph("Agence immobilière agréée · membre de la CDAIM", discret(8.5f)));
        agence.addElement(new Paragraph("Cocody Riviera 3, Cité Allabra, Abidjan", discret(8.5f)));
        agence.addElement(new Paragraph("27 22 46 91 38 · 05 55 43 55 55 · info@factimmobilier-ci.com", discret(8.5f)));
        t.addCell(agence);
        PdfPCell droite = cellule(null);
        Paragraph p = new Paragraph(titre, gras(15, NUIT));
        p.setAlignment(Element.ALIGN_RIGHT);
        droite.addElement(p);
        for (String m : mentions) {
            Paragraph x = new Paragraph(m, normal(9.5f));
            x.setAlignment(Element.ALIGN_RIGHT);
            droite.addElement(x);
        }
        t.addCell(droite);
        doc.add(t);
        doc.add(trait(OCRE, 2f, 10, 12));
    }

    /** Un titre de section : petites capitales bleu nuit. */
    public static Paragraph section(String texte) {
        Paragraph p = new Paragraph(texte.toUpperCase(Locale.FRANCE), gras(9.5f, NUIT));
        p.setSpacingBefore(12);
        p.setSpacingAfter(6);
        return p;
    }

    public static PdfPCell cellule(Phrase p) {
        PdfPCell c = p == null ? new PdfPCell() : new PdfPCell(p);
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    /** Cellule d'en-tête d'un tableau de données. */
    public static PdfPCell titreColonne(String texte, int alignement) {
        PdfPCell c = new PdfPCell(new Phrase(texte, gras(8.5f, NUIT)));
        c.setBackgroundColor(FOND);
        c.setBorderColor(FILET);
        c.setPadding(5);
        c.setHorizontalAlignment(alignement);
        return c;
    }

    /** Cellule de données d'un tableau. */
    public static PdfPCell donnee(String texte, int alignement, boolean fort) {
        PdfPCell c = new PdfPCell(new Phrase(texte, fort ? gras(9, Color.BLACK) : normal(9)));
        c.setBorderColor(FILET);
        c.setPadding(5);
        c.setHorizontalAlignment(alignement);
        return c;
    }

    /** Une case de chiffre clé : libellé discret, valeur en gras. */
    public static PdfPCell chiffre(String libelle, String valeur, Color couleur) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(FOND);
        c.setBorderColor(FILET);
        c.setPadding(8);
        c.addElement(new Paragraph(libelle, discret(8)));
        Paragraph v = new Paragraph(valeur, gras(13, couleur));
        v.setSpacingBefore(2);
        c.addElement(v);
        return c;
    }

    public static Paragraph trait(Color couleur, float epaisseur, float avant, float apres) {
        Paragraph p = new Paragraph(" ");
        p.setSpacingBefore(avant - 10);
        p.setSpacingAfter(apres);
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.BOTTOM);
        c.setBorderWidthBottom(epaisseur);
        c.setBorderColorBottom(couleur);
        c.setFixedHeight(2);
        t.addCell(c);
        p.add(t);
        return p;
    }

    /** Numérote les pages en pied : « Édité le … · page n ». */
    public static PdfPageEventHelper pied(String mention) {
        return new PdfPageEventHelper() {
            @Override
            public void onEndPage(PdfWriter w, Document d) {
                ColumnText.showTextAligned(w.getDirectContent(), Element.ALIGN_CENTER,
                        new Phrase(mention + " · page " + w.getPageNumber(), discret(7.5f)),
                        (d.left() + d.right()) / 2, d.bottom() - 18, 0);
            }
        };
    }

    public static String fcfa(long n) {
        return entier(n) + " FCFA";
    }

    public static String entier(long n) {
        return String.format(Locale.FRANCE, "%,d", n).replace(' ', ' ').replace(' ', ' ');
    }
}
