package com.eburtis.fact.service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import com.eburtis.fact.utils.Pdf;
import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;

import org.springframework.stereotype.Service;

/**
 * Le relevé de gestion d'un propriétaire, en PDF (SIG-17, § 4.8) : situation de ses biens, loyers
 * encaissés sur la période, honoraires de l'agence selon son mandat, travaux à sa charge, et le
 * montant qui lui revient. Un plan de situation place ses bâtiments les uns par rapport aux autres.
 */
@Service
public class ReleveService {

    private static final DateTimeFormatter JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy");
    private static final DateTimeFormatter EDITION = DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH'h'mm");

    /** Les totaux du relevé ; le montant à reverser n'est jamais négatif à l'affichage, le solde l'est. */
    public record Totaux(long encaisse, long honoraires, long travaux, long aReverser) {
    }

    public static Totaux totaux(JsonNode r) {
        long enc = 0, hon = 0, trv = 0;
        for (JsonNode b : r.get("batiments")) {
            enc += b.get("encaisse").asLong();
            hon += b.get("honoraires").asLong();
            trv += b.get("travaux").asLong();
        }
        return new Totaux(enc, hon, trv, enc - hon - trv);
    }

    public byte[] pdf(JsonNode proprietaire, JsonNode r, String periode, LocalDate du, LocalDate au) {
        Totaux t = totaux(r);
        try (ByteArrayOutputStream sortie = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 40, 40, 36, 46);
            PdfWriter ecrivain = PdfWriter.getInstance(doc, sortie);
            ecrivain.setPageEvent(Pdf.pied("Relevé de gestion de " + texte(proprietaire, "nomComplet")
                    + " · édité le " + EDITION.format(LocalDateTime.now())));
            doc.addTitle("Relevé de gestion " + texte(proprietaire, "reference"));
            doc.addAuthor("FACT Immobilier");
            doc.open();
            Pdf.entete(doc, "RELEVÉ DE GESTION", capitale(periode),
                    "Du " + JOUR.format(du) + " au " + JOUR.format(au.plusMonths(1).minusDays(1)));

            // ------------------------------------------------------------- mandant
            PdfPTable qui = new PdfPTable(1);
            qui.setWidthPercentage(100);
            PdfPCell c = new PdfPCell();
            c.setBackgroundColor(Pdf.FOND);
            c.setBorderColor(Pdf.FILET);
            c.setPadding(10);
            c.addElement(new Paragraph("PROPRIÉTAIRE", Pdf.discret(8)));
            c.addElement(new Paragraph(texte(proprietaire, "nomComplet") + "  ·  " + texte(proprietaire, "reference"), Pdf.gras(11, Color.BLACK)));
            String contact = String.join(" · ", List.of(texte(proprietaire, "telephone"), texte(proprietaire, "email"))
                    .stream().filter(x -> !x.equals("-")).toList());
            if (!contact.isEmpty()) {
                c.addElement(new Paragraph(contact, Pdf.normal(9)));
            }
            qui.addCell(c);
            qui.setSpacingAfter(10);
            doc.add(qui);

            // ------------------------------------------------------- chiffres clés
            PdfPTable cles = new PdfPTable(4);
            cles.setWidthPercentage(100);
            cles.addCell(Pdf.chiffre("Loyers encaissés", Pdf.fcfa(t.encaisse()), Pdf.NUIT));
            cles.addCell(Pdf.chiffre("Honoraires de gestion", moins(t.honoraires()), Pdf.NUIT));
            cles.addCell(Pdf.chiffre("Travaux à votre charge", moins(t.travaux()), Pdf.NUIT));
            cles.addCell(Pdf.chiffre("Montant à vous reverser", Pdf.fcfa(t.aReverser()), t.aReverser() < 0 ? Pdf.CRITIQUE : Pdf.BON));
            doc.add(cles);
            if (t.aReverser() < 0) {
                Paragraph n = new Paragraph("Les travaux dépassent les loyers encaissés : le solde sera imputé sur le prochain relevé.", Pdf.discret(8.5f));
                n.setSpacingBefore(4);
                doc.add(n);
            }

            // ----------------------------------------------------------- vos biens
            doc.add(Pdf.section("Vos biens"));
            JsonNode bats = r.get("batiments");
            List<JsonNode> places = new ArrayList<>();
            bats.forEach(b -> {
                if (!b.get("latitude").isNull()) {
                    places.add(b);
                }
            });
            PdfPTable biens = new PdfPTable(places.isEmpty() ? new float[]{1} : new float[]{1.05f, 1f});
            biens.setWidthPercentage(100);
            if (!places.isEmpty()) {
                PdfPCell plan = new PdfPCell(Image.getInstance(plan(ecrivain, places, 250, 190)));
                plan.setBorderColor(Pdf.FILET);
                plan.setPadding(4);
                biens.addCell(plan);
            }
            PdfPTable liste = new PdfPTable(new float[]{0.3f, 2.4f, 1f, 1.2f});
            liste.setWidthPercentage(100);
            liste.addCell(Pdf.titreColonne("", Element.ALIGN_LEFT));
            liste.addCell(Pdf.titreColonne("Bâtiment", Element.ALIGN_LEFT));
            liste.addCell(Pdf.titreColonne("Occupés", Element.ALIGN_RIGHT));
            liste.addCell(Pdf.titreColonne("Mandat", Element.ALIGN_LEFT));
            int n = 0;
            for (JsonNode b : bats) {
                boolean place = !b.get("latitude").isNull();
                liste.addCell(Pdf.donnee(place ? String.valueOf(++n) : "", Element.ALIGN_LEFT, true));
                liste.addCell(Pdf.donnee(texte(b, "nom") + "\n" + (b.hasNonNull("quartier") ? texte(b, "quartier") + ", " : "") + texte(b, "commune"),
                        Element.ALIGN_LEFT, false));
                liste.addCell(Pdf.donnee(b.get("occupes").asInt() + " / " + b.get("lots").asInt(), Element.ALIGN_RIGHT, false));
                liste.addCell(Pdf.donnee(b.hasNonNull("mandat") ? texte(b, "mandat") + "\n" + taux(b.get("taux")) + " d'honoraires"
                        : "Aucun mandat en vigueur", Element.ALIGN_LEFT, false));
            }
            PdfPCell cl = new PdfPCell(liste);
            cl.setBorder(0);
            cl.setPaddingLeft(places.isEmpty() ? 0 : 8);
            biens.addCell(cl);
            doc.add(biens);

            // --------------------------------------------------- détail des loyers
            doc.add(Pdf.section("Loyers de la période"));
            PdfPTable lots = new PdfPTable(new float[]{2f, 0.8f, 2f, 1.2f, 1.3f, 1.2f});
            lots.setWidthPercentage(100);
            lots.setHeaderRows(1);
            for (String[] e : new String[][]{{"Bâtiment", "G"}, {"Lot", "G"}, {"Locataire", "G"}, {"Loyer mensuel", "D"},
                    {"Encaissé", "D"}, {"Reste dû à ce jour", "D"}}) {
                lots.addCell(Pdf.titreColonne(e[0], e[1].equals("G") ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT));
            }
            for (JsonNode l : r.get("lots")) {
                lots.addCell(Pdf.donnee(texte(l, "batiment"), Element.ALIGN_LEFT, false));
                lots.addCell(Pdf.donnee(texte(l, "numero"), Element.ALIGN_LEFT, false));
                lots.addCell(Pdf.donnee(l.hasNonNull("occupant") ? texte(l, "occupant") : texte(l, "statut"), Element.ALIGN_LEFT, false));
                lots.addCell(Pdf.donnee(l.hasNonNull("loyer") ? Pdf.fcfa(l.get("loyer").asLong()) : "-", Element.ALIGN_RIGHT, false));
                lots.addCell(Pdf.donnee(Pdf.fcfa(l.get("encaisse").asLong()), Element.ALIGN_RIGHT, false));
                long reste = l.get("impaye").asLong();
                lots.addCell(Pdf.donnee(reste > 0 ? Pdf.fcfa(reste) : "-", Element.ALIGN_RIGHT, reste > 0));
            }
            doc.add(lots);

            // ------------------------------------------------------------ travaux
            doc.add(Pdf.section("Travaux à votre charge"));
            if (r.get("travaux").isEmpty()) {
                doc.add(new Paragraph("Aucune intervention à votre charge n'a été clôturée sur la période.", Pdf.discret(9)));
            } else {
                PdfPTable tr = new PdfPTable(new float[]{1f, 3f, 2f, 1f, 1.3f});
                tr.setWidthPercentage(100);
                for (String e : new String[]{"Référence", "Intervention", "Bâtiment", "Clôturée le", "Coût"}) {
                    tr.addCell(Pdf.titreColonne(e, e.equals("Coût") ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT));
                }
                for (JsonNode x : r.get("travaux")) {
                    tr.addCell(Pdf.donnee(texte(x, "reference"), Element.ALIGN_LEFT, false));
                    tr.addCell(Pdf.donnee(texte(x, "titre"), Element.ALIGN_LEFT, false));
                    tr.addCell(Pdf.donnee(texte(x, "batiment"), Element.ALIGN_LEFT, false));
                    tr.addCell(Pdf.donnee(JOUR.format(LocalDate.parse(texte(x, "le"))), Element.ALIGN_LEFT, false));
                    tr.addCell(Pdf.donnee(Pdf.fcfa(x.get("cout").asLong()), Element.ALIGN_RIGHT, false));
                }
                doc.add(tr);
            }

            // ------------------------------------------------------------- calcul
            doc.add(Pdf.section("Calcul du montant à reverser"));
            PdfPTable calc = new PdfPTable(new float[]{3f, 1.4f});
            calc.setWidthPercentage(60);
            calc.setHorizontalAlignment(Element.ALIGN_LEFT);
            ligne(calc, "Loyers et charges encaissés", Pdf.fcfa(t.encaisse()), false);
            ligne(calc, "Honoraires de gestion (taux de votre mandat)", moins(t.honoraires()), false);
            ligne(calc, "Travaux à votre charge", moins(t.travaux()), false);
            ligne(calc, "Montant à vous reverser", Pdf.fcfa(t.aReverser()), true);
            doc.add(calc);
            Paragraph fin = new Paragraph("Relevé établi par FACT Immobilier. Pour toute question, contactez votre gestionnaire.", Pdf.discret(8.5f));
            fin.setSpacingBefore(14);
            doc.add(fin);
            doc.close();
            return sortie.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Plan de situation : chaque bâtiment est placé selon ses coordonnées, à l'échelle, numéroté
     * comme dans la liste. Une barre d'échelle donne les distances.
     */
    private static PdfTemplate plan(PdfWriter w, List<JsonNode> places, float l, float h) {
        PdfContentByte cb = w.getDirectContent();
        PdfTemplate t = cb.createTemplate(l, h);
        t.setColorFill(Pdf.FOND);
        t.rectangle(0, 0, l, h);
        t.fill();
        double minLat = Double.MAX_VALUE, maxLat = -Double.MAX_VALUE, minLon = Double.MAX_VALUE, maxLon = -Double.MAX_VALUE;
        for (JsonNode b : places) {
            double la = b.get("latitude").asDouble(), lo = b.get("longitude").asDouble();
            minLat = Math.min(minLat, la);
            maxLat = Math.max(maxLat, la);
            minLon = Math.min(minLon, lo);
            maxLon = Math.max(maxLon, lo);
        }
        // Une étendue minimale d'environ 1 km, pour qu'un seul bâtiment ne remplisse pas le cadre.
        double cosLat = Math.cos(Math.toRadians((minLat + maxLat) / 2));
        double etLat = Math.max(maxLat - minLat, 0.009), etLon = Math.max(maxLon - minLon, 0.009 / cosLat);
        double cLat = (minLat + maxLat) / 2, cLon = (minLon + maxLon) / 2;
        float marge = 24;
        // Même échelle sur les deux axes (en mètres) pour ne pas déformer les distances.
        double mParPtX = etLon * 111_320 * cosLat / (l - 2 * marge), mParPtY = etLat * 110_540 / (h - 2 * marge);
        double mParPt = Math.max(mParPtX, mParPtY);

        // Quadrillage discret
        t.setColorStroke(Pdf.FILET);
        t.setLineWidth(0.5f);
        for (float x = 0; x <= l; x += 25) {
            t.moveTo(x, 0);
            t.lineTo(x, h);
        }
        for (float y = 0; y <= h; y += 25) {
            t.moveTo(0, y);
            t.lineTo(l, y);
        }
        t.stroke();

        BaseFont police;
        try {
            police = BaseFont.createFont(BaseFont.HELVETICA_BOLD, BaseFont.WINANSI, BaseFont.NOT_EMBEDDED);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        int n = 0;
        for (JsonNode b : places) {
            n++;
            double dx = (b.get("longitude").asDouble() - cLon) * 111_320 * cosLat / mParPt;
            double dy = (b.get("latitude").asDouble() - cLat) * 110_540 / mParPt;
            float x = (float) (l / 2 + dx), y = (float) (h / 2 + dy);
            t.setColorFill(Pdf.OCRE);
            t.circle(x, y, 7.5f);
            t.fill();
            t.setColorStroke(Color.WHITE);
            t.setLineWidth(1.2f);
            t.circle(x, y, 7.5f);
            t.stroke();
            t.beginText();
            t.setFontAndSize(police, 8);
            t.setColorFill(Color.WHITE);
            t.showTextAligned(Element.ALIGN_CENTER, String.valueOf(n), x, y - 2.8f, 0);
            t.endText();
        }

        // Barre d'échelle : une longueur ronde proche du quart de la largeur
        double metres = mParPt * l / 4;
        double rond = Math.pow(10, Math.floor(Math.log10(metres)));
        for (double m : new double[]{1, 2, 5}) {
            if (m * Math.pow(10, Math.floor(Math.log10(metres))) <= metres) {
                rond = m * Math.pow(10, Math.floor(Math.log10(metres)));
            }
        }
        float lg = (float) (rond / mParPt);
        t.setColorStroke(Pdf.NUIT);
        t.setLineWidth(1.5f);
        t.moveTo(8, 9);
        t.lineTo(8 + lg, 9);
        t.stroke();
        t.beginText();
        t.setFontAndSize(police, 7);
        t.setColorFill(Pdf.NUIT);
        t.showTextAligned(Element.ALIGN_LEFT, rond >= 1000 ? (rond / 1000 == Math.floor(rond / 1000) ? (long) (rond / 1000) + " km"
                : rond / 1000 + " km") : (long) rond + " m", 8, 13, 0);
        t.showTextAligned(Element.ALIGN_RIGHT, "Nord en haut", l - 8, h - 14, 0);
        t.endText();
        return t;
    }

    /** « - 553 200 FCFA », mais « 0 FCFA » quand il n'y a rien à déduire. */
    private static String moins(long n) {
        return n == 0 ? Pdf.fcfa(0) : "- " + Pdf.fcfa(n);
    }

    private static void ligne(PdfPTable t, String libelle, String valeur, boolean total) {
        PdfPCell a = Pdf.donnee(libelle, Element.ALIGN_LEFT, total);
        PdfPCell b = Pdf.donnee(valeur, Element.ALIGN_RIGHT, total);
        if (total) {
            a.setBackgroundColor(Pdf.FOND);
            b.setBackgroundColor(Pdf.FOND);
        }
        t.addCell(a);
        t.addCell(b);
    }

    private static String taux(JsonNode t) {
        return t == null || t.isNull() ? "-" : (t.asDouble() == Math.floor(t.asDouble()) ? String.valueOf(t.asLong())
                : String.valueOf(t.asDouble()).replace('.', ',')) + " %";
    }

    private static String capitale(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String texte(JsonNode n, String champ) {
        return n != null && n.hasNonNull(champ) ? n.get(champ).asText() : "-";
    }
}
