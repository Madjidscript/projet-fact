package com.eburtis.fact.service;

import static com.eburtis.fact.utils.Pdf.BON;
import static com.eburtis.fact.utils.Pdf.CRITIQUE;
import static com.eburtis.fact.utils.Pdf.FILET;
import static com.eburtis.fact.utils.Pdf.FOND;
import static com.eburtis.fact.utils.Pdf.GRIS;
import static com.eburtis.fact.utils.Pdf.NUIT;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.eburtis.fact.utils.Pdf;
import com.fasterxml.jackson.databind.JsonNode;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

import org.springframework.stereotype.Service;

/**
 * Le PDF d'un état des lieux : les parties, le logement, chaque pièce et l'état de ses éléments
 * (à la sortie, l'état noté à l'entrée en regard), les photos, les compteurs, les clés et les
 * deux signatures recueillies sur place.
 */
@Service
public class EtatDesLieuxService {

    private static final ZoneId ABIDJAN = ZoneId.of("Africa/Abidjan");
    private static final DateTimeFormatter LE = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH'h'mm", Locale.FRANCE);
    private static final Map<String, String> ETATS = Map.of("BON", "Bon état", "USAGE", "Usure normale", "MAUVAIS", "Mauvais état",
            "HORS_SERVICE", "Hors service");
    private static final Map<String, String> COMPTEURS = Map.of("eau", "Eau (SODECI)", "electricite", "Électricité (CIE)", "gaz", "Gaz");
    private static final Color ATTENTION = new Color(0xC9, 0x7A, 0x12);

    private final StockagePhotos stockagePhotos;

    public EtatDesLieuxService(StockagePhotos stockagePhotos) {
        this.stockagePhotos = stockagePhotos;
    }

    public byte[] pdf(JsonNode e, JsonNode entree, List<String[]> photos) {
        boolean sortie = "SORTIE".equals(e.get("type").asText());
        String titre = sortie ? "ÉTAT DES LIEUX DE SORTIE" : "ÉTAT DES LIEUX D'ENTRÉE";
        String le = LE.format(OffsetDateTime.parse(e.get("faitLe").asText()).atZoneSameInstant(ABIDJAN));
        Map<String, String> avant = new HashMap<>();
        if (entree != null) {
            for (JsonNode p : entree.get("pieces")) {
                for (JsonNode x : p.get("elements")) {
                    avant.put(cle(p.get("piece").asText(), x.get("nom").asText()), x.get("etat").asText());
                }
            }
        }
        Map<String, List<String>> photosParPiece = new LinkedHashMap<>();
        for (String[] p : photos) {
            photosParPiece.computeIfAbsent(p[0], (k) -> new ArrayList<>()).add(p[1]);
        }
        try (ByteArrayOutputStream sortieFlux = new ByteArrayOutputStream()) {
            Document doc = new Document(PageSize.A4, 44, 44, 40, 54);
            PdfWriter w = PdfWriter.getInstance(doc, sortieFlux);
            w.setPageEvent(Pdf.pied("État des lieux " + texte(e, "reference") + " · FACT Immobilier"));
            doc.addTitle(titre + " " + texte(e, "reference"));
            doc.addAuthor("FACT Immobilier");
            doc.open();
            Pdf.entete(doc, titre, "N° " + texte(e, "reference"), "Fait le " + le);

            // ---------------------------------------------------------------- parties
            PdfPTable parties = new PdfPTable(2);
            parties.setWidthPercentage(100);
            parties.setSpacingAfter(6);
            parties.addCell(encadre("OCCUPANT", texte(e, "occupant"), "Réf. " + texte(e, "referenceOccupant") + " · bail " + texte(e, "referenceBail")));
            String lieu = texte(e, "batiment") + " — lot " + texte(e, "numeroLot")
                    + (e.hasNonNull("libelleTypeLot") ? " (" + texte(e, "libelleTypeLot") + ")" : "");
            String ou = (e.hasNonNull("quartier") ? texte(e, "quartier") + ", " : "") + texte(e, "commune")
                    + (e.hasNonNull("adresse") ? " · " + texte(e, "adresse") : "");
            parties.addCell(encadre("LOGEMENT", lieu, ou + " · propriétaire : " + texte(e, "proprietaire")));
            doc.add(parties);
            if (sortie && entree != null) {
                doc.add(new Paragraph("À comparer avec l'état des lieux d'entrée " + texte(entree, "reference") + " du "
                        + LE.format(OffsetDateTime.parse(entree.get("faitLe").asText()).atZoneSameInstant(ABIDJAN)) + ".", Pdf.discret(8.5f)));
            }

            // ---------------------------------------------------------------- pièces
            for (JsonNode p : e.get("pieces")) {
                String piece = p.get("piece").asText();
                doc.add(Pdf.section(piece));
                boolean comparer = sortie && entree != null;
                PdfPTable t = new PdfPTable(comparer ? new float[]{2.2f, 1.1f, 1.1f, 2.6f} : new float[]{2.4f, 1.2f, 3f});
                t.setWidthPercentage(100);
                t.addCell(Pdf.titreColonne("Élément", Element.ALIGN_LEFT));
                if (comparer) {
                    t.addCell(Pdf.titreColonne("À l'entrée", Element.ALIGN_LEFT));
                }
                t.addCell(Pdf.titreColonne(comparer ? "À la sortie" : "État", Element.ALIGN_LEFT));
                t.addCell(Pdf.titreColonne("Observations", Element.ALIGN_LEFT));
                for (JsonNode x : p.get("elements")) {
                    t.addCell(Pdf.donnee(x.get("nom").asText(), Element.ALIGN_LEFT, false));
                    if (comparer) {
                        String a = avant.get(cle(piece, x.get("nom").asText()));
                        t.addCell(etat(a));
                    }
                    t.addCell(etat(x.get("etat").asText()));
                    t.addCell(Pdf.donnee(x.hasNonNull("note") ? x.get("note").asText() : "", Element.ALIGN_LEFT, false));
                }
                doc.add(t);
                if (p.hasNonNull("note")) {
                    Paragraph n = new Paragraph(p.get("note").asText(), Pdf.normal(9));
                    n.setSpacingBefore(4);
                    doc.add(n);
                }
                List<String> ph = photosParPiece.remove(piece);
                if (ph != null) {
                    doc.add(vignettes(ph));
                }
            }
            if (!photosParPiece.isEmpty()) {
                doc.add(Pdf.section("Autres photos"));
                doc.add(vignettes(photosParPiece.values().stream().flatMap(List::stream).toList()));
            }

            // ------------------------------------------------------ compteurs, clés
            JsonNode c = e.get("compteurs");
            if ((c != null && c.size() > 0) || e.hasNonNull("clesRemises")) {
                doc.add(Pdf.section("Compteurs et clés"));
                PdfPTable t = new PdfPTable(4);
                t.setWidthPercentage(100);
                int n = 0;
                if (c != null) {
                    var it = c.fields();
                    while (it.hasNext()) {
                        var f = it.next();
                        t.addCell(Pdf.chiffre(COMPTEURS.getOrDefault(f.getKey(), f.getKey()), f.getValue().asText(), NUIT));
                        n++;
                    }
                }
                if (e.hasNonNull("clesRemises")) {
                    t.addCell(Pdf.chiffre(sortie ? "Clés rendues" : "Clés remises", e.get("clesRemises").asText(), NUIT));
                    n++;
                }
                while (n++ % 4 != 0) {
                    t.addCell(Pdf.cellule(null));
                }
                doc.add(t);
            }
            if (e.hasNonNull("observations")) {
                doc.add(Pdf.section("Observations générales"));
                doc.add(new Paragraph(texte(e, "observations"), Pdf.normal(9.5f)));
            }

            // ------------------------------------------------------------ signatures
            // Le titre, la mention et les deux signatures restent sur la même page.
            PdfPTable s = new PdfPTable(2);
            s.setWidthPercentage(100);
            s.setKeepTogether(true);
            s.setSpacingBefore(6);
            PdfPCell tete = Pdf.cellule(null);
            tete.setColspan(2);
            tete.addElement(Pdf.section("Signatures"));
            Paragraph mention = new Paragraph("État des lieux établi contradictoirement sur place, le " + le
                    + ". Les deux parties reconnaissent l'exactitude des constats ci-dessus.", Pdf.discret(8.5f));
            mention.setSpacingAfter(8);
            tete.addElement(mention);
            s.addCell(tete);
            s.addCell(signature("Pour l'occupant", texte(e, "signataire"), texte(e, "signatureOccupant")));
            s.addCell(signature("Pour FACT Immobilier", texte(e, "creePar").replaceAll("\\s*\\([^)]*\\)$", ""), texte(e, "signatureAgent")));
            doc.add(s);
            doc.close();
            return sortieFlux.toByteArray();
        } catch (Exception ex) {
            throw new IllegalStateException("Le PDF de l'état des lieux n'a pas pu être produit", ex);
        }
    }

    private static String cle(String piece, String element) {
        return piece.trim().toLowerCase(Locale.FRANCE) + "|" + element.trim().toLowerCase(Locale.FRANCE);
    }

    private static PdfPCell etat(String code) {
        Color couleur = code == null ? GRIS : switch (code) {
            case "BON" -> BON;
            case "USAGE" -> NUIT;
            case "MAUVAIS" -> ATTENTION;
            default -> CRITIQUE;
        };
        PdfPCell c = new PdfPCell(new Phrase(code == null ? "—" : ETATS.getOrDefault(code, code), Pdf.gras(9, couleur)));
        c.setBorderColor(FILET);
        c.setPadding(5);
        return c;
    }

    private static PdfPCell encadre(String titre, String principal, String detail) {
        PdfPCell c = new PdfPCell();
        c.setBackgroundColor(FOND);
        c.setBorderColor(FILET);
        c.setPadding(9);
        c.addElement(new Paragraph(titre, Pdf.gras(8, GRIS)));
        c.addElement(new Paragraph(principal, Pdf.gras(10.5f, Color.BLACK)));
        c.addElement(new Paragraph(detail, Pdf.discret(8.5f)));
        return c;
    }

    private PdfPTable vignettes(List<String> chemins) {
        PdfPTable t = new PdfPTable(4);
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        int n = 0;
        for (String chemin : chemins) {
            PdfPCell c = Pdf.cellule(null);
            c.setPadding(3);
            try {
                Image i = Image.getInstance(stockagePhotos.lire(chemin));
                i.scaleToFit(118, 90);
                c.addElement(i);
            } catch (Exception ex) {
                c.addElement(new Paragraph("Photo illisible", Pdf.discret(8)));
            }
            t.addCell(c);
            n++;
        }
        while (n++ % 4 != 0) {
            t.addCell(Pdf.cellule(null));
        }
        return t;
    }

    private PdfPCell signature(String titre, String nom, String chemin) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(FILET);
        c.setPadding(10);
        c.setMinimumHeight(120);
        c.addElement(new Paragraph(titre, Pdf.gras(8.5f, GRIS)));
        Paragraph n = new Paragraph(nom, Pdf.gras(10, Color.BLACK));
        n.setSpacingAfter(8);
        c.addElement(n);
        try {
            Image i = Image.getInstance(stockagePhotos.lire(chemin));
            i.scaleToFit(200, 80);
            c.addElement(i);
        } catch (Exception ex) {
            c.addElement(new Paragraph("Signature illisible", Pdf.discret(8)));
        }
        return c;
    }

    private static String texte(JsonNode n, String champ) {
        return n.hasNonNull(champ) ? n.get(champ).asText() : "";
    }
}
